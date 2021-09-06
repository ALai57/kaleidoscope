# Cloud infrastructure

Cloud infrastructure is provisioned using Terraform.

Terraform for the following AWS resources:
- ECS Fargate to run the backend
- RDS Postgres for persistence
- S3 for storing the tf backend
- ECS Fargate to run Keycloak IDP
- Single ALB to route traffic to both IDP and to the application

