
## Certs

##############################################################
# Load Balancer
##############################################################
data "aws_alb" "main" {
  name   = "andrewslai-production"
}

# Created this manually in AWS console - go to AWS Certificate Manager
# to request a cert. Then create DNS records using the AWS console.
data "aws_acm_certificate" "issued" {
  domain   = "caheriaguilar.com"
  statuses = ["ISSUED"]
}

data "aws_lb_listener" "main" {
  load_balancer_arn = "${data.aws_alb.main.arn}"
  port              = 443
}

# Use existing target group to point traffic to existing web server
data "aws_alb_target_group" "main" {
  name = "andrewslai-production"
}

# Add SSL Cert for caheriaguilar.com to existing LB
resource "aws_lb_listener_certificate" "caheriaguilar_cert" {
  listener_arn    = "${data.aws_lb_listener.main.arn}"
  certificate_arn = "${data.aws_acm_certificate.issued.arn}"
}
