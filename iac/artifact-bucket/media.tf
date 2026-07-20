##############################################################
# Media object store (photos)
# A single per-environment bucket keyed by the object's intrinsic
# identity (media/<photo-id>/<category>.<ext>) — never by tenant,
# hostname, or env. Prod serves + writes kal-media-prod; each
# ephemeral env writes its own disposable kal-eph-<slug>-media
# bucket and reads through to prod read-only. See
# docs/operations.md ("Media object storage") and
# plans/2026-07-18-object-storage-model.
##############################################################

resource "aws_s3_bucket" "kal_media_prod" {
  bucket = "kal-media-prod"
}

# Versioning makes deletes — including reconciliation's quarantine — reversible.
resource "aws_s3_bucket_versioning" "kal_media_prod" {
  bucket = aws_s3_bucket.kal_media_prod.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Lifecycle rules bound storage cost — including that of orphaned bytes the
# append-only store leaves behind (reclaimed offline by task media:reconcile).
resource "aws_s3_bucket_lifecycle_configuration" "kal_media_prod" {
  bucket = aws_s3_bucket.kal_media_prod.id

  # Reclaim storage from uploads that were started but never completed.
  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  # Auto cold-tier every object (write-once/read-sometimes media) so even an
  # orphan costs cold-tier pricing, and expire superseded noncurrent versions.
  rule {
    id     = "cold-tier-and-noncurrent-expiry"
    status = "Enabled"
    filter {}
    transition {
      days          = 0
      storage_class = "INTELLIGENT_TIERING"
    }
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }

  # Reconciliation quarantines orphans under trash/ (reversible via versioning);
  # expire them a few weeks later.
  rule {
    id     = "expire-trash"
    status = "Enabled"
    filter {
      prefix = "trash/"
    }
    expiration {
      days = 28
    }
  }

  # noncurrent_version_expiration requires versioning to already be configured.
  depends_on = [aws_s3_bucket_versioning.kal_media_prod]
}

##############################################################
# Ephemeral IAM — media grants
# A second inline policy on the existing kaleidoscope_ephemeral user (defined in
# s3.tf), isolating the media concern from its kal-ephemeral-s3 policy. Grants
# the per-env media bucket lifecycle + object read-write, and READ-ONLY access to
# prod media for the ephemeral read-through overlay (writes can only ever reach
# the env's own bucket — enforced structurally by ReadThroughFS). The read grant
# is scoped to kal-media-prod only, so ephemeral read-through resolves existing
# photos against kal-media-prod (set PROD_MEDIA_BUCKET=kal-media-prod on deploy;
# consolidate into it before relying on ephemeral read-through).
##############################################################

resource "aws_iam_user_policy" "kal_ephemeral_media" {
  name = "kal-ephemeral-media-s3"
  user = aws_iam_user.kaleidoscope_ephemeral.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "PerEnvMediaBucketLifecycle"
        Effect = "Allow"
        Action = [
          "s3:CreateBucket",
          "s3:DeleteBucket",
          "s3:ListBucket",
          "s3:GetBucketLocation",
        ]
        Resource = ["arn:aws:s3:::kal-eph-*-media"]
      },
      {
        Sid    = "PerEnvMediaObjectReadWrite"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
        ]
        Resource = ["arn:aws:s3:::kal-eph-*-media/*"]
      },
      {
        Sid    = "ProdMediaReadOnly"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:ListBucket",
        ]
        Resource = [
          aws_s3_bucket.kal_media_prod.arn,
          "${aws_s3_bucket.kal_media_prod.arn}/*",
        ]
      },
    ]
  })
}
