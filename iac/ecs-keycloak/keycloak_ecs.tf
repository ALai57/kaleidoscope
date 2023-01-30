#https://github.com/turnerlabs/terraform-ecs-fargate/blob/master/env/dev/ecs.tf

##############################################################
# Variables
##############################################################

variable "KC_DB_PASSWORD" {
  description = "Database password"
}

variable "KC_DB_USERNAME" {
  description = "Database username"
}

variable "KC_DB_DATABASE" {
  description = "Database"
}

variable "KC_DB_URL_HOST" {
  description = "Database host url"
}

variable "KC_DB" {
  description = "Database vendor"
}

variable "KEYCLOAK_USER" {
  description = "Keycloak admin username"
}

variable "KEYCLOAK_PASSWORD" {
  description = "Keycloak admin password"
}

##############################################################
# Data sources to get VPC, subnets and security group details
##############################################################
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "all" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

data "aws_security_group" "default" {
  vpc_id = "${data.aws_vpc.default.id}"
  name   = "default"
}

##############################################################
# Security group to allow traffic
##############################################################

resource "aws_security_group" "keycloak_allow_http_https" {
  name        = "keycloak_allow_http_https"
  description = "Allow http and https traffic"
  vpc_id      = "${data.aws_vpc.default.id}"

  ingress {
    description = "HTTP Traffic"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "HTTP Traffic"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS Traffic"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "HTTPS Traffic"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "keycloak_allow_http_https",
    Service = "keycloak"
  }
}


##############################################################
# Roles
# Create a role for keycloak that can get images and start tasks
##############################################################

# https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_execution_IAM_role.html
resource "aws_iam_role" "ecsTaskExecutionRole" {
  name               = "keycloak-production-ecs"
  assume_role_policy = "${data.aws_iam_policy_document.assume_role_policy.json}"
}

# Generates an IAM policy document in JSON format
data "aws_iam_policy_document" "assume_role_policy" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Attaches a managed IAM Policy to an IAM role
resource "aws_iam_role_policy_attachment" "ecsTaskExecutionRole_policy" {
  role       = "${aws_iam_role.ecsTaskExecutionRole.name}"
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

##############################################################
# Load Balancer
##############################################################

data "aws_alb" "main" {
  name   = "andrewslai-production"
}

resource "aws_alb_target_group" "main" {
  name                 = "keycloak-production"
  port                 = 443
  protocol             = "HTTP"
  vpc_id               = "${data.aws_vpc.default.id}"
  target_type          = "ip"

  health_check {
     interval = 180
     unhealthy_threshold = 4
  }
  lifecycle {
      create_before_destroy = true
  }

  deregistration_delay = 20

}

# Created this manually in AWS console
data "aws_acm_certificate" "issued" {
  domain   = "keycloak.andrewslai.com"
  statuses = ["ISSUED"]
}

data "aws_lb_listener" "main" {
  load_balancer_arn = "${data.aws_alb.main.arn}"
  port              = 443
}

# Add SSL Cert for keycloak.andrewslai.com to existing LB
resource "aws_lb_listener_certificate" "keycloak_cert" {
  listener_arn    = "${data.aws_lb_listener.main.arn}"
  certificate_arn = "${data.aws_acm_certificate.issued.arn}"
}

# Add a rule to the existing load balancer for my app
resource "aws_lb_listener_rule" "host_based_routing" {
  listener_arn = "${data.aws_lb_listener.main.arn}"
  priority     = 20

  action {
    type = "forward"
    target_group_arn  = "${aws_alb_target_group.main.arn}"
  }

  condition {
    host_header {
      values = ["keycloak.andrewslai.com"]
    }
  }
}

##############################################################
# ECS
##############################################################

resource "aws_ecs_cluster" "keycloak_cluster" {
  name = "keycloak"
}

resource "aws_ecs_cluster_capacity_providers" "keycloak" {
  cluster_name = aws_ecs_cluster.keycloak_cluster.name

  capacity_providers = ["FARGATE"]
}

resource "aws_ecs_task_definition" "keycloak_task" {
  family                = "keycloak"
  requires_compatibilities = ["FARGATE"]
  network_mode          = "awsvpc"
  cpu                   = "256"
  memory                = "512"
  execution_role_arn    = "${aws_iam_role.ecsTaskExecutionRole.arn}"

  # PROXY_ADDRESS_FORWARDING is necesssary because Keycloak will be behind an
# AWS ALB which is doing SSL termination
 container_definitions = <<DEFINITION
[
  {
    "name": "keycloak",
    "image": "758589815425.dkr.ecr.us-east-1.amazonaws.com/keycloak_ecr:latest",
    "essential": true,
    "portMappings": [
      {
        "protocol": "tcp",
        "containerPort": 8080,
        "hostPort": 8080
      }
    ],
    "environment": [
      {"name": "KC_DB"         , "value": "${var.KC_DB}"},
      {"name": "KC_DB_URL_HOST", "value": "${var.KC_DB_URL_HOST}"},
      {"name": "KC_DB_DATABASE", "value": "${var.KC_DB_DATABASE}"},
      {"name": "KC_DB_USERNAME", "value": "${var.KC_DB_USERNAME}"},
      {"name": "KC_DB_PASSWORD", "value": "${var.KC_DB_PASSWORD}"},

      {"name": "KC_HOSTNAME_STRICT", "value": "false"},
      {"name": "KC_EDGE"           , "value": "proxy"},
      {"name": "KC_HTTP_ENABLED"   , "value": "true"},
      {"name": "KC_FEATURES"       , "value": "token-exchange"},

      {"name": "KEYCLOAK_USER"    , "value": "${var.KEYCLOAK_USER}"},
      {"name": "KEYCLOAK_PASSWORD", "value": "${aws_secretsmanager_secret_version.keycloak_admin_password.secret_string}"}
    ],
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {
        "awslogs-group": "/fargate/service/keycloak-production",
        "awslogs-region": "us-east-1",
        "awslogs-stream-prefix": "ecs"
      }
    }
  }
]
DEFINITION
}

resource "aws_ecs_service" "keycloak_service" {
  name            = "keycloak-service"
  cluster         = "${aws_ecs_cluster.keycloak_cluster.id}"
  launch_type     = "FARGATE"
  task_definition = "${aws_ecs_task_definition.keycloak_task.arn}"
  desired_count   = 1

  network_configuration {
    security_groups  = ["${data.aws_security_group.default.id}", "${aws_security_group.keycloak_allow_http_https.id}"]
    subnets          = data.aws_subnets.all.ids
    assign_public_ip = "true"
  }

  load_balancer {
    target_group_arn = "${aws_alb_target_group.main.id}"
    container_name   = "keycloak"
    container_port   = "8080"
  }

  # workaround for https://github.com/hashicorp/terraform/issues/12634
  #depends_on = [aws_alb_listener.http]

  # [after initial apply] don't override changes made to task_definition
  # from outside of terraform (i.e.; fargate cli)
  ##lifecycle {
    ##ignore_changes = ["task_definition"]
  ##}
}

resource "aws_cloudwatch_log_group" "logs" {
  name              = "/fargate/service/keycloak-production"
  retention_in_days = 90
}

##############################################################
# SECRETS
##############################################################
resource "aws_secretsmanager_secret" "keycloak_admin_password" {
   name = "keycloak-admin-password"
   description = "Keycloak admin password"
}

resource "aws_secretsmanager_secret_version" "keycloak_admin_password" {
   secret_id = "${aws_secretsmanager_secret.keycloak_admin_password.id}"
   secret_string = "${var.KEYCLOAK_PASSWORD}"
}
