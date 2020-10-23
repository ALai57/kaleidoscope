terraform {
  backend "s3" {
    bucket     = "andrewslai-tf"
    key        = "elastic-beanstalk/terraform.tfstate"
    region     = "us-east-1"
  }
}
