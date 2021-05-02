terraform {
  backend "s3" {
    bucket     = "andrewslai-tf"
    key        = "wedding-bucket/terraform.tfstate"
    region     = "us-east-1"
  }
}
