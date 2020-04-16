provider "aws" {
  access_key = "${var.access_key}"
  secret_key = "${var.secret_key}"
  region     = "${var.region}"
}

resource "aws_s3_bucket" "andrewslai_s3" {
  bucket = "andrewslai-website-s3"
}

resource "aws_s3_bucket_object" "andrewslai_artifact" {
  bucket = "${aws_s3_bucket.andrewslai_s3.id}"
  key    = "deployment.zip"
  source = "deployment.zip"
}

resource "aws_acm_certificate" "cert" {
  domain_name       = "andrewslai.com"
  validation_method = "DNS"

  tags = {
    Environment = "test"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# resource "aws_route53_record" "validation" {
  # zone_id = "${aws_route53_zone.public_zone.zone_id}"
  # name = "${aws_acm_certificate.cert.domain_validation_options.0.resource_record_name}"
  # type = "${aws_acm_certificate.cert.domain_validation_options.0.resource_record_type}"
  # records = ["${aws_acm_certificate.cert.domain_validation_options.0.resource_record_value}"]
  # ttl = "300"
# }

resource "aws_elastic_beanstalk_application" "andrewslai_app" {
  name        = "andrewslai_website"
  description = "My personal website"
}

resource "aws_elastic_beanstalk_environment" "andrewslai_env" {
  name                = "staging"
  application         = "${aws_elastic_beanstalk_application.andrewslai_app.name}"
  solution_stack_name = "64bit Amazon Linux 2018.03 v2.12.14 running Docker 18.06.1-ce"

  setting {
    namespace = "aws:ec2:vpc"
    name      = "VPCId"
    value     = "${var.default_vpc}"
  }

  setting {
    namespace = "aws:ec2:vpc"
    name      = "ELBSubnets"
    value     = "${var.subnets}"
  }
  setting {
    namespace = "aws:ec2:vpc"
    name      = "Subnets"
    value     = "${var.subnets}"
  }
  setting {
    namespace = "aws:autoscaling:launchconfiguration"
    name      = "SecurityGroups"
    value     = "${var.security_groups}"
  }
  setting {
    namespace = "aws:elasticbeanstalk:application:environment"
    name      = "ANDREWSLAI_DB_PASSWORD"
    value     = "${var.ANDREWSLAI_DB_PASSWORD}"
  }
  setting {
    namespace = "aws:elasticbeanstalk:application:environment"
    name      = "ANDREWSLAI_DB_USER"
    value     = "${var.ANDREWSLAI_DB_USER}"
  }
  setting {
    namespace = "aws:elasticbeanstalk:application:environment"
    name      = "ANDREWSLAI_DB_NAME"
    value     = "${var.ANDREWSLAI_DB_NAME}"
  }
  setting {
    namespace = "aws:elasticbeanstalk:application:environment"
    name      = "ANDREWSLAI_DB_HOST"
    value     = "${var.ANDREWSLAI_DB_HOST}"
  }
  setting {
    namespace = "aws:elasticbeanstalk:application:environment"
    name      = "ANDREWSLAI_DB_PORT"
    value     = "${var.ANDREWSLAI_DB_PORT}"
  }

}

resource "aws_elastic_beanstalk_application_version" "andrewslai_app_version" {
  name        = "andrewslai-application-version"
  application = "${aws_elastic_beanstalk_application.andrewslai_app.name}"
  description = "application version created by terraform"
  bucket      = "${aws_s3_bucket.andrewslai_s3.id}"
  key         = "${aws_s3_bucket_object.andrewslai_artifact.id}"
}
