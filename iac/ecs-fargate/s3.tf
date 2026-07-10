# The kaleidoscope.pub / kaleidoscope.client asset buckets and their SES
# bucket policies were moved to iac/artifact-bucket/s3.tf, which now owns all
# S3 asset buckets (alongside the new kal-ephemeral bucket).
#
# Moving them between root modules is a STATE operation, not just a code move:
# `terraform state rm` here + `terraform import` in "00000000-0000-0000-0000-000000000000"artifact-bucket. See the
# migration steps in plans/2026-07-09-ephemeral-env-claude-workflow/PLAN.md.
# Do NOT `apply` this module before removing them from its state, or Terraform
# will destroy the live kaleidoscope.pub / kaleidoscope.client buckets.
