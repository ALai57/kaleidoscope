##############################################################
# IAM user for Fly.io → S3 access
# (Fly has no IAM role support, so credentials are injected
#  as secrets via `fly secrets set`)
##############################################################

resource "aws_iam_user" "kaleidoscope_fly" {
  name = "kaleidoscope-fly"
}

resource "aws_iam_user_policy" "kaleidoscope_fly_s3" {
  name = "kaleidoscope-s3"
  user = aws_iam_user.kaleidoscope_fly.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:GetObjectTagging",
          "s3:PutObjectTagging",
          "s3:GetObjectVersion",
          "s3:DeleteObjectVersion",
          "s3:AbortMultipartUpload",
        ]
        Resource = [
          "arn:aws:s3:::andrewslai.com/*",
          "arn:aws:s3:::caheriaguilar.com/*",
          "arn:aws:s3:::sahiltalkingcents.com/*",
          "arn:aws:s3:::wedding/*",
          "arn:aws:s3:::kaleidoscope.pub/*",
          "arn:aws:s3:::kaleidoscope.client/*",
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "s3:ListBucket",
          "s3:ListBucketVersions",
        ]
        Resource = [
          "arn:aws:s3:::andrewslai.com",
          "arn:aws:s3:::caheriaguilar.com",
          "arn:aws:s3:::sahiltalkingcents.com",
          "arn:aws:s3:::wedding",
          "arn:aws:s3:::kaleidoscope.pub",
          "arn:aws:s3:::kaleidoscope.client",
        ]
      },
    ]
  })
}

resource "aws_iam_access_key" "kaleidoscope_fly" {
  user = aws_iam_user.kaleidoscope_fly.name
}

output "kaleidoscope_fly_access_key_id" {
  value = aws_iam_access_key.kaleidoscope_fly.id
}

output "kaleidoscope_fly_secret_access_key" {
  value     = aws_iam_access_key.kaleidoscope_fly.secret
  sensitive = true
}

##############################################################
# S3 buckets
##############################################################

resource "aws_s3_bucket" "blog_buckets" {
  for_each = toset(["andrewslai", "caheriaguilar", "sahiltalkingcents", "andrewslai.com", "caheriaguilar.com", "sahiltalkingcents.com"])
  bucket = each.key
}

## The Email receiving must be configured by hand in SES
resource "aws_s3_bucket_policy" "allow_access_from_ses" {
  for_each = aws_s3_bucket.blog_buckets
  bucket = each.value.id
  policy = <<-EOF
{
  "Version":"2012-10-17",
  "Statement":[
    {
      "Sid":"AllowSESPuts",
      "Effect":"Allow",
      "Principal":{
        "Service":"ses.amazonaws.com"
      },
      "Action":"s3:PutObject",
      "Resource":"arn:aws:s3:::${each.value.id}/*",
      "Condition":{
        "StringEquals":{
          "AWS:SourceAccount":"758589815425",
          "AWS:SourceArn": "arn:aws:ses:us-east-1:758589815425:receipt-rule-set/${each.value.id}-emails:receipt-rule/myrule"
        }
      }
    }
  ]
}
EOF
}
