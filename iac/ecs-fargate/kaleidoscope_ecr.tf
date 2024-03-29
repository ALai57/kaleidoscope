
resource "aws_ecr_repository" "kaleidoscope_ecr" {
  name                 = "andrewslai_ecr"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_repository" "kaleidoscope_fluentbit_ecr" {
  name                 = "andrewslai_fluentbit_ecr"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

## Rename the container registry as part of renaming from
## andrewslai -> kaleidoscope
resource "aws_ecr_repository" "kaleidoscope_ecr_new" {
  name                 = "kaleidoscope_ecr"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_repository" "kaleidoscope_fluentbit_ecr_new" {
  name                 = "kaleidoscope_fluentbit_ecr"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}
