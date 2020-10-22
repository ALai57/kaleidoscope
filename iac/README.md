## There are several different resources that need to be created

# AWS Credentials
In order to do any AWS operations, you must have an AWS access token and secret
key. Those are provided in a file called `provider_secrets.tfvars`, which is NOT
checked into source control. An example template file is provided,
`provider_secrets.tfvars.example`, which should be filled in and renamed

# Terraform backend 
This S3 bucket holds Terraform state. It was created via the AWS
console and has restrictive access settings. The current name is `andrewslai-tf`

# S3 bucket for artifacts
This S3 bucket holds artifacts - Docker images and JAR files. It is created
through terraform and accessed through the script
`artifact-bucket/terraform-bucket`, for example `./terraform-bucket init`
