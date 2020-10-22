terraform {
  backend "s3" {
    bucket     = "andrewslai-tf"
    key        = "aws-rds-postgres/terraform.tfstate"
    region     = "us-east-1"
  }
}
