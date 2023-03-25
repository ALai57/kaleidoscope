resource "aws_s3_bucket" "kaleidoscope_assets_bucket" {
  for_each = toset(["kaleidoscope.pub"])
  bucket = each.key
}

## The Email receiving must be configured by hand in SES
resource "aws_s3_bucket_policy" "allow_access_from_ses" {
  for_each = aws_s3_bucket.kaleidoscope_assets_bucket
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
