provider "aws" {
  access_key = "${var.access_key}"
  secret_key = "${var.secret_key}"
  region     = "${var.region}"
}

resource "aws_s3_bucket" "testbkt" {
  bucket = "tftest-s3"
}

resource "aws_s3_bucket_object" "testbkt" {
  bucket = "${aws_s3_bucket.testbkt.id}"
  key    = "beanstalk/deployment.zip"
  source = "deployment.zip"
}

resource "aws_elastic_beanstalk_application" "tftest_app" {
  name        = "tf-test-app"
  description = "tf-test-app"
}

resource "aws_elastic_beanstalk_environment" "tftest_env" {
  name                = "tf-test-env"
  application         = "${aws_elastic_beanstalk_application.tftest_app.name}"
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

resource "aws_elastic_beanstalk_application_version" "tftest_app_version" {
  name        = "tf-test-application-version"
  application = "${aws_elastic_beanstalk_application.tftest_app.name}"
  description = "application version created by terraform"
  bucket      = "${aws_s3_bucket.testbkt.id}"
  key         = "${aws_s3_bucket_object.testbkt.id}"
}
