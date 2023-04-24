## https://github.com/terraform-aws-modules/terraform-aws-rds/tree/master/examples/complete-postgres

##############################################################
# Data sources to get VPC, subnets and security group details
##############################################################
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "all" {
  filter {
    name = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

data "aws_security_group" "default" {
  vpc_id = data.aws_vpc.default.id
  name   = "default"
}

##############################################################
# Variables
##############################################################

variable "andrewslai_db_username" {}
variable "andrewslai_db_password" {}
variable "andrewslai_home_ip"     {}
variable "andrewslai_db_port"     {default = "5432"}

##############################################################
# Security Groups
##############################################################
resource "aws_security_group" "allow_vpc_traffic" {
  name        = "allow_vpc_traffic"
  description = "Allow VPC inbound traffic"
  vpc_id      = "${data.aws_vpc.default.id}"

  ingress {
    description = "Traffic from VPC"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = ["${data.aws_vpc.default.cidr_block}"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "allow_vpc_traffic"
  }
}

resource "aws_security_group" "allow_home_traffic" {
  name        = "allow_home_traffic"
  description = "Allow inbound traffic from home"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "Traffic from home"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.andrewslai_home_ip]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "allow_home_traffic"
  }
}

##############################################################
# DB
##############################################################

module "db" {
  source = "terraform-aws-modules/rds/aws"
  version = "~> 5.0"

  identifier = "andrewslai-postgres"

  engine            = "postgres"
  engine_version    = "14.4"
  instance_class    = "db.t2.micro"
  allocated_storage = 5
  storage_encrypted = false

  # kms_key_id        = "arm:aws:kms:<region>:<account id>:key/<kms key id>"
  db_name = "andrewslai"

  # NOTE: Do NOT use 'user' as the value for 'username' as it throws:
  # "Error creating DB Instance: InvalidParameterValue: MasterUsername
  # user cannot be used as it is a reserved word used by the engine"
  username = var.andrewslai_db_username

  password = var.andrewslai_db_password
  port     = var.andrewslai_db_port

  vpc_security_group_ids = [aws_security_group.allow_home_traffic.id, aws_security_group.allow_vpc_traffic.id]

  maintenance_window = "Mon:00:00-Mon:03:00"
  backup_window      = "03:00-06:00"

  # disable backups to create DB faster
  backup_retention_period = 0

  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  # DB subnet group
  create_db_subnet_group = true
  subnet_ids = data.aws_subnets.all.ids

  # DB parameter group
  family = "postgres14"

  # DB option group
  major_engine_version = "14.4"

  # Snapshot name upon DB deletion
  #final_snapshot_identifier = "andrewslai-postgres"

  # Database Deletion Protection
  deletion_protection = false

  publicly_accessible = true
}
