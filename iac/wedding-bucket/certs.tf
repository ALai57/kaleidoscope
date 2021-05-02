##############################################################
# Load Balancer
##############################################################
data "aws_alb" "main" {
  name   = "andrewslai-production"
}

# Created this manually in AWS console
data "aws_acm_certificate" "issued" {
  domain   = "caheriaguilar.and.andrewslai.com"
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

# Add SSL Cert for caheriaguilar.and.andrewslai.com to existing LB
resource "aws_lb_listener_certificate" "wedding_cert" {
  listener_arn    = "${data.aws_lb_listener.main.arn}"
  certificate_arn = "${data.aws_acm_certificate.issued.arn}"
}

# Add a rule to the existing load balancer for my app
# Unnecessary because the existing listener rule redirects traffic to the main
# web-server
#resource "aws_lb_listener_rule" "host_based_routing" {
  #listener_arn = "${data.aws_lb_listener.main.arn}"
  #priority     = 20
#
  #action {
    #type = "forward"
    #target_group_arn  = "${data.aws_alb_target_group.main.arn}"
  #}
#
  #condition {
    #host_header {
      #values = ["caheriaguilar.and.andrewslai.com"]
    #}
  #}
#}
