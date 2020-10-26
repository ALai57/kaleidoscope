terraform {
  backend "s3" {
    bucket     = "andrewslai-tf"
    key        = "ecs-fargate/terraform.tfstate"
    region     = "us-east-1"
  }
}
