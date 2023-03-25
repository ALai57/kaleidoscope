terraform {
  backend "s3" {
    bucket     = "andrewslai-tf"
    key        = "andrewslai-bucket/terraform.tfstate"
    region     = "us-east-1"
  }
}
