resource "aws_s3_bucket" "andrewslai_bucket" {
  bucket = "andrewslai"
}

## The Email receiving was configured all by hand in SES
resource "aws_s3_bucket_policy" "allow_access_from_s3s" {
  bucket = aws_s3_bucket.andrewslai_bucket.id
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
      "Resource":"arn:aws:s3:::andrewslai/*",
      "Condition":{
        "StringEquals":{
          "AWS:SourceAccount":"758589815425",
          "AWS:SourceArn": "arn:aws:ses:us-east-1:758589815425:receipt-rule-set/andrewslai-emails:receipt-rule/myrule"
        }
      }
    }
  ]
}
EOF
}
