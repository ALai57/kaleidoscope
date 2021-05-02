terraform {
  backend "s3" {
    bucket     = "andrewslai-tf"
    key        = "ecs-keycloak/terraform.tfstate"
    region     = "us-east-1"
  }
}
