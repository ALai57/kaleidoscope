#https://github.com/turnerlabs/terraform-ecs-fargate/blob/master/env/dev/ecs.tf

##############################################################
# Variables
##############################################################

variable "ANDREWSLAI_DB_PASSWORD" {
  description = "Database password"
}

variable "ANDREWSLAI_DB_USER" {
  description = "Database username"
}

variable "ANDREWSLAI_DB_NAME" {
  description = "Database password"
}

variable "ANDREWSLAI_DB_HOST" {
  description = "Database host url"
}

variable "ANDREWSLAI_DB_PORT" {
  description = "Database port"
}

variable "ANDREWSLAI_AUTH_REALM" {
  description = "Keycloak realm to auth into"
}

variable "ANDREWSLAI_AUTH_URL" {
  description = "Keycloak URL"
}

variable "ANDREWSLAI_AUTH_CLIENT" {
  description = "Keycloak client id"
}

variable "ANDREWSLAI_AUTH_SECRET" {
  description = "Keycloak client secret"
}

variable "ANDREWSLAI_STATIC_CONTENT_TYPE" {
  description = "How to serve static content"
}

# Necessary because it seems like the DefaultRegionProviderChain walks down a chain of
# providers to find its region. If it cannot find the AWS region in environment, etc
# then it calls EC2MetadataUtils
#https://github.com/aws/containers-roadmap/issues/337
#https://github.com/spring-cloud/spring-cloud-aws/issues/556
variable "AWS_DEFAULT_REGION" {
  description = "AWS region."
  default = "us-east-1"
}

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
# Secret values
##############################################################

variable "example_secrets" {
  default = {
    andrewslai_auth_secret = "FILLMEIN"
    andrewslai_db_password = "FILLMEIN"
  }

  type = map(string)
}

resource "aws_secretsmanager_secret" "andrewslai_secrets" {
  name = "andrewslai-secrets"
}

resource "aws_secretsmanager_secret_version" "andrewslai_secret_version" {
  secret_id     = aws_secretsmanager_secret.andrewslai_secrets.id
  secret_string = jsonencode(var.example_secrets)
}

##############################################################
# Roles
##############################################################


data "aws_iam_policy_document" "assume_role_policy" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}


# https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_execution_IAM_role.html
resource "aws_iam_role" "ecsTaskExecutionRole" {
  name               = "andrewslai-production-ecs"
  assume_role_policy = "${data.aws_iam_policy_document.assume_role_policy.json}"
}

resource "aws_iam_role_policy" "ecsTaskExecutionRolePolicy" {
  name   = "${aws_iam_role.ecsTaskExecutionRole.name}"
  role   = "${aws_iam_role.ecsTaskExecutionRole.id}"
  policy = <<-EOF
  {
    "Version": "2012-10-17",
    "Statement": [
      {
        "Action": [
                "ecr:GetAuthorizationToken",
                "ecr:BatchCheckLayerAvailability",
                "ecr:GetDownloadUrlForLayer",
                "ecr:BatchGetImage",
                "logs:CreateLogStream",
                "logs:CreateLogGroup",
                "logs:PutLogEvents"
        ],
        "Effect": "Allow",
        "Resource": "*"
      },
      {
        "Action": [
                "secretsmanager:GetSecretValue"
        ],
        "Effect": "Allow",
        "Resource": ["${aws_secretsmanager_secret.andrewslai_secrets.arn}"]
      }
    ]
  }
  EOF
}

## For the actual task that is running
resource "aws_iam_role" "ecsTaskRole" {
  name               = "andrewslai-task"
  assume_role_policy = "${data.aws_iam_policy_document.assume_role_policy.json}"
}

resource "aws_iam_role_policy" "ecsTaskRolePolicy" {
  name   = "${aws_iam_role.ecsTaskRole.name}"
  role   = "${aws_iam_role.ecsTaskRole.id}"
  policy = <<-EOF
  {
    "Version": "2012-10-17",
    "Statement": [
      {
            "Sid": "VisualEditor0",
            "Effect": "Allow",
            "Action": [
                "s3:DeleteObjectTagging",
                "s3:GetObjectRetention",
                "s3:DeleteObjectVersion",
                "s3:GetObjectVersionTagging",
                "s3:PutObjectVersionTagging",
                "s3:DeleteObjectVersionTagging",
                "s3:PutObject",
                "s3:GetObject",
                "s3:AbortMultipartUpload",
                "s3:GetObjectTagging",
                "s3:PutObjectTagging",
                "s3:DeleteObject",
                "s3:GetObjectVersion"
            ],
            "Resource": "arn:aws:s3:::*/*"
        },
        {
            "Sid": "VisualEditor1",
            "Effect": "Allow",
            "Action": [
                "s3:ListBucketVersions",
                "s3:ListBucket"
            ],
            "Resource": "arn:aws:s3:::*"
        }
    ]
  }
  EOF
}

##############################################################
# Load Balancer
##############################################################

resource "aws_alb" "main" {
  name = "andrewslai-production"

  # launch lbs in public or private subnets based on "internal" variable
  internal = false

  subnets = data.aws_subnets.all.ids
  security_groups = ["${data.aws_security_group.default.id}", "${aws_security_group.ecs_allow_http_https.id}"]

}

resource "aws_alb_target_group" "main" {
  name                 = "andrewslai-production"
  port                 = 443
  protocol             = "HTTP"
  vpc_id               = "${data.aws_vpc.default.id}"
  target_type          = "ip"

  health_check {
     interval = 45
     unhealthy_threshold = 4
     path = "/"
  }

  lifecycle {
      create_before_destroy = true
  }

  deregistration_delay = 20

}

resource "aws_lb_listener" "http_listener" {
  load_balancer_arn = "${aws_alb.main.arn}"
  protocol          = "HTTP"
  port              = 80

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

data "aws_acm_certificate" "issued" {
  domain   = "andrewslai.com"
  statuses = ["ISSUED"]
}

resource "aws_lb_listener" "https_listener" {
  load_balancer_arn = "${aws_alb.main.arn}"
  port              = "443"
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-2016-08"
  certificate_arn   = "${data.aws_acm_certificate.issued.arn}"

  default_action {
    type             = "forward"
    target_group_arn = "${aws_alb_target_group.main.arn}"
  }
}

# Moving to using api.andrewslai.com
# Manually issued via AWS Console
data "aws_acm_certificate" "api_andrewslai_issued" {
  domain   = "api.andrewslai.com"
  statuses = ["ISSUED"]
}


data "aws_lb_listener" "main" {
  load_balancer_arn = "${aws_alb.main.arn}"
  port              = 443
}

# Add SSL Cert for keycloak.andrewslai.com to existing LB
resource "aws_lb_listener_certificate" "api_andrewslai_cert" {
  listener_arn    = "${data.aws_lb_listener.main.arn}"
  certificate_arn = "${data.aws_acm_certificate.api_andrewslai_issued.arn}"
}

# Add a rule to the existing load balancer for my app
resource "aws_lb_listener_rule" "host_based_routing" {
  listener_arn = "${data.aws_lb_listener.main.arn}"
  priority     = 50

  action {
    type = "forward"
    target_group_arn  = "${aws_alb_target_group.main.arn}"
  }

  condition {
    host_header {
      values = ["api.andrewslai.com"]
    }
  }
}

##############################################################
# ECS
##############################################################

resource "aws_ecs_cluster" "andrewslai_cluster" {
  name = "andrewslai"
}

resource "aws_ecs_cluster_capacity_providers" "example" {
  cluster_name = aws_ecs_cluster.andrewslai_cluster.name

  capacity_providers = ["FARGATE"]
}

# Seems like Loki's docs are out of date:
# https://github.com/grafana/loki/issues/5271
# For logging, see the FluentBit output Plugin:
# https://docs.fluentbit.io/manual/pipeline/outputs/loki
resource "aws_ecs_task_definition" "andrewslai_task" {
  family                = "andrewslai-site"
  requires_compatibilities = ["FARGATE"]
  network_mode          = "awsvpc"
  cpu                   = "512"
  memory                = "1024"
  execution_role_arn    = "${aws_iam_role.ecsTaskExecutionRole.arn}"
  task_role_arn         = "${aws_iam_role.ecsTaskRole.arn}"

 container_definitions = <<DEFINITION
[
 {
    "essential": true,
    "image": "amazon/aws-for-fluent-bit:2.28.3",
    "name": "log_router",
    "environment": [{"name": "FLB_LOG_LEVEL", "value": "debug"}],
    "firelensConfiguration": {
        "type": "fluentbit",
        "options": {
            "enable-ecs-log-metadata": "true"
        }
    },
    "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
            "awslogs-group": "/fargate/service/andrewslai-production-firelens",
            "awslogs-region": "us-east-1",
            "awslogs-create-group": "true",
            "awslogs-stream-prefix": "firelens"
        }
    },
    "memoryReservation": 128
  },
  {
    "name": "andrewslai",
    "image": "758589815425.dkr.ecr.us-east-1.amazonaws.com/andrewslai_ecr:latest",
    "essential": true,
    "portMappings": [
      {
        "protocol": "tcp",
        "containerPort": 5000,
        "hostPort": 5000
      }
    ],
    "secrets": [
      {
        "name": "ANDREWSLAI_DB_PASSWORD",
        "valueFrom": "${aws_secretsmanager_secret.andrewslai_secrets.arn}:andrewslai_db_password::"
      },
      {
        "name": "ANDREWSLAI_AUTH_SECRET",
        "valueFrom": "${aws_secretsmanager_secret.andrewslai_secrets.arn}:andrewslai_auth_secret::"
      }
     ],
    "environment": [
      {
        "name": "ANDREWSLAI_DB_USER",
        "value": "${var.ANDREWSLAI_DB_USER}"
      },
      {
        "name": "ANDREWSLAI_DB_NAME",
        "value": "${var.ANDREWSLAI_DB_NAME}"
      },
      {
        "name": "ANDREWSLAI_DB_HOST",
        "value": "${var.ANDREWSLAI_DB_HOST}"
      },
      {
        "name": "ANDREWSLAI_DB_PORT",
        "value": "${var.ANDREWSLAI_DB_PORT}"
      },
      {
        "name": "ANDREWSLAI_AUTH_REALM",
        "value": "${var.ANDREWSLAI_AUTH_REALM}"
      },
      {
        "name": "ANDREWSLAI_AUTH_URL",
        "value": "${var.ANDREWSLAI_AUTH_URL}"
      },
      {
        "name": "ANDREWSLAI_AUTH_CLIENT",
        "value": "${var.ANDREWSLAI_AUTH_CLIENT}"
      },
      {
        "name": "ANDREWSLAI_STATIC_CONTENT_TYPE",
        "value": "${var.ANDREWSLAI_STATIC_CONTENT_TYPE}"
      },
      {
        "name": "AWS_DEFAULT_REGION",
        "value": "${var.AWS_DEFAULT_REGION}"
      }
    ],
    "logConfiguration": {
      "logDriver": "awsfirelens",
      "options": {
          "Name":          "loki",
          "host":          "logs-prod3.grafana.net",
          "port":          "443",
          "http_user":     "309152",
          "labels":        "job=firelens",
          "tls":           "on",
          "net.keepalive": "false",
          "remove_keys":   "container_id, ecs_task_arn",
          "label_keys":    "$container_name,$ecs_task_definition,$source,$ecs_cluster",
          "line_format":   "key_value"
      },
      "secretOptions": [
        {
          "name": "http_passwd",
          "valueFrom": "${aws_secretsmanager_secret.andrewslai_secrets.arn}:andrewslai_loki_password::"
        }
      ]
    }
  }
]
DEFINITION
}

resource "aws_ecs_service" "andrewslai_service" {
  name            = "andrewslai-service"
  cluster         = "${aws_ecs_cluster.andrewslai_cluster.id}"
  launch_type     = "FARGATE"
  task_definition = "${aws_ecs_task_definition.andrewslai_task.arn}"
  desired_count   = 1

  network_configuration {
    security_groups  = ["${data.aws_security_group.default.id}", "${aws_security_group.ecs_allow_http_https.id}"]
    subnets          = data.aws_subnets.all.ids
    assign_public_ip = "true"
  }

  load_balancer {
    target_group_arn = "${aws_alb_target_group.main.id}"
    container_name   = "andrewslai"
    container_port   = "5000"
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
  name              = "/fargate/service/andrewslai-production"
  retention_in_days = 90
}
