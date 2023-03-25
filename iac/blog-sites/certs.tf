
## Certs

##############################################################
# Load Balancer
##############################################################
data "aws_alb" "main" {
  name = "andrewslai-production"
}

data "aws_lb_listener" "main" {
  load_balancer_arn = data.aws_alb.main.arn
  port              = 443
}

# Created this manually in AWS console - go to AWS Certificate Manager
# to request a cert. Then create DNS records using the AWS console.
data "aws_acm_certificate" "blog_certs" {
  for_each = toset(["caheriaguilar.com", "sahiltalkingcents.com", "andrewslai.com"])
  domain   = each.key
  statuses = ["ISSUED"]
}

# Add SSL Cert for sahiltalkingcents.com to existing LB
# Go to AWS Certificate Manager. Request new cert for site.
# Then go to Route53 and add an A record that redirects to the Load balancer
# Manually added an A record to Route53 to redirect traffic to ELB
resource "aws_lb_listener_certificate" "listener_certs" {
  for_each = data.aws_acm_certificate.blog_certs
  listener_arn    = data.aws_lb_listener.main.arn
  certificate_arn = each.value.arn
}
