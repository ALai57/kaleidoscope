##############################################################
# Kaleidoscope app asset buckets
# Moved here from iac/ecs-fargate/s3.tf (see that file). Serve
# the SPA shell (kaleidoscope.client) and shared static assets
# (kaleidoscope.pub). SES receiving is configured by hand in SES.
##############################################################

resource "aws_s3_bucket" "kaleidoscope_assets_bucket" {
  for_each = toset(["kaleidoscope.pub", "kaleidoscope.client"])
  bucket   = each.key
}

## The Email receiving must be configured by hand in SES
resource "aws_s3_bucket_policy" "allow_access_from_ses" {
  for_each = aws_s3_bucket.kaleidoscope_assets_bucket
  bucket   = each.value.id
  policy   = <<-EOF
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

##############################################################
# Ephemeral test-environment bucket
# One shared bucket for the frontend build + /static chrome; each
# ephemeral env is isolated by an eph-<slug>/ key prefix rather than
# a bucket per env for THAT content. (Photos are the exception: they
# now use a per-env kal-eph-<slug>-media bucket with read-through to
# prod — see media.tf, which grants this same user the
# CreateBucket/DeleteBucket + read-only-prod perms that model needs.)
# The object read/write/delete creds below feed AWS_ACCESS_KEY_ID/
# _SECRET in .env.fly.staging (used by the local build-frontend/down
# scripts and by the Fly app, which reads / and /static/* from here).
##############################################################

resource "aws_s3_bucket" "kal_ephemeral" {
  bucket = "kal-ephemeral"
}

resource "aws_iam_user" "kaleidoscope_ephemeral" {
  name = "kaleidoscope-ephemeral"
}

resource "aws_iam_user_policy" "kaleidoscope_ephemeral_s3" {
  name = "kal-ephemeral-s3"
  user = aws_iam_user.kaleidoscope_ephemeral.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ObjectReadWrite"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
        ]
        Resource = ["${aws_s3_bucket.kal_ephemeral.arn}/*"]
      },
      {
        Sid      = "BucketList"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [aws_s3_bucket.kal_ephemeral.arn]
      },
    ]
  })
}

resource "aws_iam_access_key" "kaleidoscope_ephemeral" {
  user = aws_iam_user.kaleidoscope_ephemeral.name
}

output "kaleidoscope_ephemeral_access_key_id" {
  value = aws_iam_access_key.kaleidoscope_ephemeral.id
}

output "kaleidoscope_ephemeral_secret_access_key" {
  value     = aws_iam_access_key.kaleidoscope_ephemeral.secret
  sensitive = true
}
