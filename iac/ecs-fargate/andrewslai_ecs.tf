#https://github.com/turnerlabs/terraform-ecs-fargate/blob/master/env/dev/ecs.tf

##############################################################
# Data sources to get VPC, subnets and security group details
##############################################################
data "aws_vpc" "default" {
  default = true
}

data "aws_subnet_ids" "all" {
  vpc_id = "${data.aws_vpc.default.id}"
}

data "aws_security_group" "default" {
  vpc_id = "${data.aws_vpc.default.id}"
  name   = "default"
}

##############################################################
# Security group to allow traffic
##############################################################

resource "aws_security_group" "ecs_allow_http_https" {
  name        = "ecs_allow_http_https"
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
    Name = "ecs_allow_http_https"
  }
}


##############################################################
# Roles
##############################################################

# https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_execution_IAM_role.html
resource "aws_iam_role" "ecsTaskExecutionRole" {
  name               = "andrewslai-production-ecs"
  assume_role_policy = "${data.aws_iam_policy_document.assume_role_policy.json}"
}

data "aws_iam_policy_document" "assume_role_policy" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy_attachment" "ecsTaskExecutionRole_policy" {
  role       = "${aws_iam_role.ecsTaskExecutionRole.name}"
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "role_policy" {
  name   = "${aws_iam_role.ecsTaskExecutionRole.name}"
  role   = "${aws_iam_role.ecsTaskExecutionRole.id}"
  policy = <<-EOF
  {
    "Version": "2012-10-17",
    "Statement": [
      {
        "Action": [
          "ecr:BatchCheckLayerAvailability",
          "ecr:BatchGetImage",
          "ecr:GetDownloadUrlForLayer"
        ],
        "Effect": "Allow",
        "Resource": "*"
      }
    ]
  }
  EOF
}

  ##{
    ##"Version": "2008-10-17",
    ##"Statement": [
      ##{
        ##"Sid": "AllowECRPull",
        ##"Effect": "Allow",
        ##"Action": [
          ##"ecr:BatchCheckLayerAvailability",
          ##"ecr:BatchGetImage",
          ##"ecr:GetDownloadUrlForLayer"
        ##]
      ##}
    ##]
  ##}
##{
    ##"Version": "2008-10-17",
    ##"Statement": [
        ##{
            ##"Sid": "AllowPushPull",
            ##"Effect": "Allow",
            ##"Principal": {
                ##"AWS": "arn:aws:iam::account-id:root"
            ##},
            ##"Action": [
                ##"ecr:GetDownloadUrlForLayer",
                ##"ecr:BatchGetImage",
                ##"ecr:BatchCheckLayerAvailability",
                ##"ecr:PutImage",
                ##"ecr:InitiateLayerUpload",
                ##"ecr:UploadLayerPart",
                ##"ecr:CompleteLayerUpload"
            ##]
        ##}
    ##]
##}

##############################################################
# ECS
##############################################################

resource "aws_ecs_cluster" "andrewslai_cluster" {
  name = "andrewslai"
  capacity_providers = ["FARGATE"]
}

resource "aws_ecs_task_definition" "andrewslai_task" {
  family                = "andrewslai-site"
  requires_compatibilities = ["FARGATE"]
  network_mode          = "awsvpc"
  cpu                   = "256"
  memory                = "512"
  execution_role_arn    = "${aws_iam_role.ecsTaskExecutionRole.arn}"

 container_definitions = <<DEFINITION
[
  {
    "name": "andrewslai",
    "image": "758589815425.dkr.ecr.us-east-1.amazonaws.com/andrewslai_ecr:latest",

    "essential": true,
    "portMappings": [
      {
        "protocol": "tcp",
        "containerPort": 80,
        "hostPort": 80
      },
      {
        "protocol": "tcp",
        "containerPort": 443,
        "hostPort": 443
      }
    ],
    "environment": [
      {
        "name": "PORT",
        "value": "40"
      },
      {
        "name": "ENVIRONMENT",
        "value": "HELLO"
      }
    ]
  }
]
DEFINITION
}


    ##"logConfiguration": {
      ##"logDriver": "awslogs",
      ##"options": {
        ##"awslogs-group": "/fargate/service/andrewslai-production",
        ##"awslogs-region": "us-east-1",
        ##"awslogs-stream-prefix": "ecs"
      ##}
    ##}

resource "aws_ecs_service" "andrewslai_service" {
  name            = "andrewslai-service"
  cluster         = "${aws_ecs_cluster.andrewslai_cluster.id}"
  launch_type     = "FARGATE"
  task_definition = "${aws_ecs_task_definition.andrewslai_task.arn}"
  desired_count   = 1

  network_configuration {
    security_groups  = ["${data.aws_security_group.default.id}", "${aws_security_group.ecs_allow_http_https.id}"]
    subnets          = ["${data.aws_subnet_ids.all.ids}"]
    assign_public_ip = "true"
  }

  #load_balancer {
    #target_group_arn = aws_alb_target_group.main.id
    #container_name   = var.container_name
    #container_port   = var.container_port
  #}

  # workaround for https://github.com/hashicorp/terraform/issues/12634
  #depends_on = [aws_alb_listener.http]

  # [after initial apply] don't override changes made to task_definition
  # from outside of terraform (i.e.; fargate cli)
  ##lifecycle {
    ##ignore_changes = ["task_definition"]
  ##}
}
