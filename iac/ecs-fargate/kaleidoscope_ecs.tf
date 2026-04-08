#https://github.com/turnerlabs/terraform-ecs-fargate/blob/master/env/dev/ecs.tf

##############################################################
# Variables
##############################################################
variable "KALEIDOSCOPE_IMAGE_NOTIFIER_TYPE" {
  description = "Image Notifier type"
  default     = "sns"
}
variable "KALEIDOSCOPE_IMAGE_NOTIFIER_ARN" { description = "SNS queue to notify upon image resize request" }

variable "KALEIDOSCOPE_DB_TYPE" { description = "Database type" }
variable "KALEIDOSCOPE_DB_USER" { description = "Database username" }
variable "KALEIDOSCOPE_DB_NAME" { description = "Database name" }
variable "KALEIDOSCOPE_DB_HOST" { description = "Database host url" }
variable "KALEIDOSCOPE_DB_PORT" { description = "Database port" }

variable "KALEIDOSCOPE_AUTH_TYPE" { description = "Type of Authentication" }
variable "KALEIDOSCOPE_AUTH_REALM" { description = "Keycloak realm to auth into" }
variable "KALEIDOSCOPE_AUTH_URL" { description = "Keycloak URL" }
variable "KALEIDOSCOPE_AUTH_CLIENT" { description = "Keycloak client id" }

variable "KALEIDOSCOPE_AUTHORIZATION_TYPE" { description = "What type of Authorization scheme to use" }
variable "KALEIDOSCOPE_STATIC_CONTENT_TYPE" { description = "How to serve static content" }
variable "KALEIDOSCOPE_BUCKET" { description = "Where to serve kaleidoscope app from" }

variable "KALEIDOSCOPE_WEDDING_AUTH_TYPE" { description = "Type of Authentication" }
variable "KALEIDOSCOPE_WEDDING_AUTHORIZATION_TYPE" { description = "What type of Authorization scheme to use" }
variable "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_TYPE" { description = "How to serve static content" }
variable "KALEIDOSCOPE_WEDDING_BUCKET" { description = "Where to serve wedding app from" }
variable "KALEIDOSCOPE_EXCEPTION_REPORTER_TYPE" { description = "Exception Reporter type" }

# Necessary because it seems like the DefaultRegionProviderChain walks down a chain of
# providers to find its region. If it cannot find the AWS region in environment, etc
# then it calls EC2MetadataUtils
#https://github.com/aws/containers-roadmap/issues/337
#https://github.com/spring-cloud/spring-cloud-aws/issues/556
variable "AWS_DEFAULT_REGION" {
  description = "AWS region."
  default     = "us-east-1"
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
  vpc_id = data.aws_vpc.default.id
  name   = "default"
}

##############################################################
# Security group to allow traffic
##############################################################


##############################################################
# Secret values
##############################################################

# Adding the new secret defaults will cause TF to detect a
# secret_version change and recreate the resource
variable "example_secrets" {
  default = {
    andrewslai_auth_secret = "FILLMEIN"
    andrewslai_db_password = "FILLMEIN"
    #andrewslai_loki_password = "FILLMEIN"
    #andrewslai_sumo_password = "FILLMEIN"
  }

  type = map(string)
}

resource "aws_secretsmanager_secret" "kaleidoscope_secrets" {
  name = "andrewslai-secrets"
}

resource "aws_secretsmanager_secret_version" "kaleidoscope_secret_version" {
  secret_id     = aws_secretsmanager_secret.kaleidoscope_secrets.id
  secret_string = jsonencode(var.example_secrets)
}

##############################################################
# Roles
##############################################################


##############################################################
# Load Balancer
##############################################################


# Created this manually in AWS console - go to AWS Certificate Manager
# to request a cert. Then create DNS records using the AWS console.
data "aws_acm_certificate" "issued" {
  domain   = "kaleidoscope.pub"
  statuses = ["ISSUED"]
}


##############################################################
# ECS
##############################################################

resource "aws_cloudwatch_log_group" "logs" {
  name              = "/fargate/service/kaleidoscope/app"
  retention_in_days = 90
}



## Send logs to andrewslai-production log group when ECS task is stopped
## https://github.com/aws-samples/amazon-ecs-stopped-tasks-cwlogs/blob/master/ecs-stopped-tasks-cwlogs.yaml
resource "aws_cloudwatch_log_group" "stopped_task_logs" {
  name              = "/fargate/services/stopped-tasks"
  retention_in_days = 90
}

resource "aws_cloudwatch_event_rule" "ecs_kill" {
  name        = "ecs-stopped-tasks-event"
  description = "Log when AWS ECS stops a task"

  event_pattern = <<EOF
{
  "source": ["aws.ecs"],
  "detail-type": ["ECS Task State Change"],
  "detail": {
    "desiredStatus": ["STOPPED"],
    "lastStatus": ["STOPPED"]
  }

}
EOF
}

resource "aws_cloudwatch_event_target" "loggroup" {
  rule      = aws_cloudwatch_event_rule.ecs_kill.name
  target_id = "SendToCloudWatchLog"
  arn       = aws_cloudwatch_log_group.stopped_task_logs.arn
}

data "aws_iam_policy_document" "eventbridge-log-publishing" {

  statement {
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents"
    ]

    resources = ["arn:aws:logs:*"]

    principals {
      identifiers = ["events.amazonaws.com", "delivery.logs.amazonaws.com"]
      type        = "Service"
    }
  }

}

resource "aws_cloudwatch_log_resource_policy" "eventbridge-log-publishing-policy" {
  policy_document = data.aws_iam_policy_document.eventbridge-log-publishing.json
  policy_name     = "eventbridge-log-publishing-policy"
}
