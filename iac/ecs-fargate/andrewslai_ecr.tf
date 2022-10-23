
resource "aws_ecr_repository" "andrewslai_ecr" {
  name                 = "andrewslai_ecr"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_repository" "andrewslai_fluentbit_ecr" {
  name                 = "andrewslai_fluentbit_ecr"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}
