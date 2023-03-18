##############################################################
# Variables
##############################################################

variable "KALEIDOSCOPE_DB_PASSWORD" {
  description = "Database password"
}

variable "KALEIDOSCOPE_DB_USER" {
  description = "Database username"
}

variable "KALEIDOSCOPE_DB_NAME" {
  description = "Database password"
}

variable "KALEIDOSCOPE_DB_HOST" {
  description = "Database host url"
}

variable "KALEIDOSCOPE_DB_PORT" {
  description = "Database port"
}

##############################################################
# Data sources to get VPC, subnets and security group details
##############################################################
data "aws_vpc" "default" {
  default = true
}

data "aws_subnet_ids" "all" {
  vpc_id = "${data.aws_vpc.default.id}"
}

data "aws_security_group" "default" {
  vpc_id = "${data.aws_vpc.default.id}"
  name   = "default"
}

data "aws_iam_policy" "eb_multicontainer_docker" {
  arn = "arn:aws:iam::aws:policy/AWSElasticBeanstalkMulticontainerDocker"
}

data "aws_iam_policy" "eb_web_tier" {
  arn = "arn:aws:iam::aws:policy/AWSElasticBeanstalkWebTier"
}

data "aws_iam_policy" "eb_worker_tier" {
  arn = "arn:aws:iam::aws:policy/AWSElasticBeanstalkWorkerTier"
}

##############################################################
# Security Groups
##############################################################


resource "aws_security_group" "allow_http_https" {
  name        = "allow_http_https"
  description = "Allow http and https traffic"
  vpc_id      = "${data.aws_vpc.default.id}"

  ingress {
    description = "HTTP Traffic"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "HTTP Traffic"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS Traffic"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "HTTPS Traffic"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "allow_http_https"
  }
}

##resource "aws_security_group" "allow_http" {
  ##name        = "allow_http"
  ##description = "Allow http traffic"
  ##vpc_id      = "${data.aws_vpc.default.id}"
##
  ##ingress {
    ##description = "HTTP Traffic"
    ##from_port   = 80
    ##to_port     = 80
    ##protocol    = "tcp"
    ##cidr_blocks = ["0.0.0.0/0"]
  ##}
##
  ##egress {
    ##description = "HTTP Traffic"
    ##from_port   = 80
    ##to_port     = 80
    ##protocol    = "tcp"
    ##cidr_blocks = ["0.0.0.0/0"]
  ##}
##
  ##tags = {
    ##Name = "allow_http"
  ##}
##}

##############################################################
# Instance profile
##############################################################

# https://registry.terraform.io/modules/JousP/iam-assumeRole/aws/2.0.1/examples/custom-role
data "aws_iam_policy_document" "role_custom_assumeRole" {
  statement {
    actions       = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
    effect        = "Allow"
  }
}

resource "aws_iam_role" "andrewslai_elasticbeanstalk_ec2_role" {
  name               = "andrewslai-elasticbeanstalk-ec2-role"
  assume_role_policy = "${data.aws_iam_policy_document.role_custom_assumeRole.json}"
}

resource "aws_iam_policy_attachment" "andrewslai_attach_eb_web_tier" {
  name       = "andrewslai_ec2_policy"
  roles      = ["${aws_iam_role.andrewslai_elasticbeanstalk_ec2_role.name}"]
  policy_arn = "${data.aws_iam_policy.eb_web_tier.arn}"
}
resource "aws_iam_policy_attachment" "andrewslai_attach_eb_worker_tier" {
  name       = "andrewslai_ec2_policy"
  roles      = ["${aws_iam_role.andrewslai_elasticbeanstalk_ec2_role.name}"]
  policy_arn = "${data.aws_iam_policy.eb_worker_tier.arn}"
}
resource "aws_iam_policy_attachment" "andrewslai_attach_eb_docker" {
  name       = "andrewslai_ec2_policy"
  roles      = ["${aws_iam_role.andrewslai_elasticbeanstalk_ec2_role.name}"]
  policy_arn = "${data.aws_iam_policy.eb_multicontainer_docker.arn}"
}

resource "aws_iam_instance_profile" "andrewslai_eb_profile" {
  name = "andrewslai_eb_profile"
  role = "${aws_iam_role.andrewslai_elasticbeanstalk_ec2_role.name}"
}

##############################################################
# Routes
# The Route53 record and acm certificate were configured manually
##############################################################

data "aws_route53_zone" "andrewslai_domain" {
  name          = "andrewslai.com"
}

data "aws_acm_certificate" "issued" {
  domain   = "andrewslai.com"
  statuses = ["ISSUED"]
}

##############################################################
# Beanstalk
##############################################################

resource "aws_elastic_beanstalk_application" "andrewslai_app" {
  name        = "andrewslai_site"
  description = "My personal website"
}

resource "aws_elastic_beanstalk_environment" "andrewslai_env" {
  name                = "production"
  application         = "${aws_elastic_beanstalk_application.andrewslai_app.name}"
  solution_stack_name = "64bit Amazon Linux 2018.03 v2.16.0 running Docker 19.03.6-ce"

  setting {
    namespace = "aws:ec2:vpc"
    name      = "ELBSubnets"
    value     = "${join(",", data.aws_subnet_ids.all.ids)}"
  }
  setting {
    namespace = "aws:elasticbeanstalk:environment"
    name      = "LoadBalancerType"
    value     = "application"
  }
  setting {
    namespace = "aws:elbv2:listener:default"
    name      = "ListenerEnabled"
    value     = "true"
  }
  setting {
    namespace = "aws:elbv2:listener:443"
    name      = "ListenerEnabled"
    value     = "true"
  }
  setting {
    namespace = "aws:elbv2:listener:443"
    name      = "Protocol"
    value     = "HTTPS"
  }
  setting {
    namespace = "aws:elbv2:listener:443"
    name      = "SSLCertificateArns"
    value     = "${data.aws_acm_certificate.issued.arn}"
  }

  setting {
    namespace = "aws:ec2:vpc"
    name      = "VPCId"
    value     = "${data.aws_vpc.default.id}"
  }
  setting {
    namespace = "aws:ec2:vpc"
    name      = "Subnets"
    value     = "${join(",", data.aws_subnet_ids.all.ids)}"
  }
  setting {
    namespace = "aws:autoscaling:launchconfiguration"
    name      = "IamInstanceProfile"
    value     = "${aws_iam_instance_profile.andrewslai_eb_profile.id}"
  }
  setting {
    namespace = "aws:autoscaling:launchconfiguration"
    name      = "SecurityGroups"
    value     = "${data.aws_security_group.default.id},${aws_security_group.allow_http_https.id}"
  }
  setting {
    namespace = "aws:elasticbeanstalk:application:environment"
    name      = "KALEIDOSCOPE_DB_PASSWORD"
    value     = "${var.KALEIDOSCOPE_DB_PASSWORD}"
  }
  setting {
    namespace = "aws:elasticbeanstalk:application:environment"
    name      = "KALEIDOSCOPE_DB_USER"
    value     = "${var.KALEIDOSCOPE_DB_USER}"
  }
  setting {
    namespace = "aws:elasticbeanstalk:application:environment"
    name      = "KALEIDOSCOPE_DB_NAME"
    value     = "${var.KALEIDOSCOPE_DB_NAME}"
  }
  setting {
    namespace = "aws:elasticbeanstalk:application:environment"
    name      = "KALEIDOSCOPE_DB_HOST"
    value     = "${var.KALEIDOSCOPE_DB_HOST}"
  }
  setting {
    namespace = "aws:elasticbeanstalk:application:environment"
    name      = "KALEIDOSCOPE_DB_PORT"
    value     = "${var.KALEIDOSCOPE_DB_PORT}"
  }

}


##############################################################
# Configure listners
##############################################################

data "aws_lb_listener" "http_listener" {
  load_balancer_arn = "${aws_elastic_beanstalk_environment.andrewslai_env.load_balancers[0]}"
  port              = 80
}

resource "aws_lb_listener_rule" "redirect_http_to_https" {
  listener_arn = "${data.aws_lb_listener.http_listener.arn}"
  priority = 1
  action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }

  condition {
    path_pattern {
      values = ["/*"]
    }
  }
}
