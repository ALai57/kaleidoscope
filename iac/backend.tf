terraform {
  backend "s3" {
    bucket     = "andrewslai-tf"
    key        = "artifact-bucket/terraform.tfstate"
    region     = "us-east-1"
  }
}
