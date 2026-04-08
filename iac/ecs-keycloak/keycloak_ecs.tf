#https://github.com/turnerlabs/terraform-ecs-fargate/blob/master/env/dev/ecs.tf

##############################################################
# Variables
##############################################################

variable "KEYCLOAK_USER" { description = "Keycloak admin username" }
variable "KEYCLOAK_PASSWORD" { description = "Keycloak admin password" }

##############################################################
# Data sources to get VPC, subnets and security group details
##############################################################

##############################################################
# Security group to allow traffic
##############################################################

##############################################################
# Roles
# Create a role for keycloak that can get images and start tasks
##############################################################

##############################################################
# Load Balancer
##############################################################

##############################################################
# ECS
##############################################################


resource "aws_cloudwatch_log_group" "logs" {
  name              = "/fargate/service/keycloak-production"
  retention_in_days = 90
}

##############################################################
# SECRETS
##############################################################
resource "aws_secretsmanager_secret" "keycloak_admin_password" {
  name        = "keycloak-admin-password"
  description = "Keycloak admin password"
}

resource "aws_secretsmanager_secret_version" "keycloak_admin_password" {
  secret_id     = aws_secretsmanager_secret.keycloak_admin_password.id
  secret_string = var.KEYCLOAK_PASSWORD
}
