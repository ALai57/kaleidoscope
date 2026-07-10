#!/usr/bin/env bash
# Shared helpers for ephemeral Fly.io test environments.
# Sourced by up / down / provision-db / deploy-app / smoke-test.
#
# A single ephemeral environment is identified by a short SLUG (from --name=<x>
# or the current git branch). Every cloud resource name derives from it:
#   Fly app          kal-eph-<slug>
#   Neon branch      eph-<slug>
#   OTEL service     kal-eph-<slug>   (also KALEIDOSCOPE_ENV)
#   S3 key prefix    eph-<slug>/      (inside the shared kal-ephemeral bucket, Phase 5)

set -euo pipefail

EPHEMERAL_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
REPO_ROOT="$(cd -- "$EPHEMERAL_DIR/../.." &>/dev/null && pwd)"

# --- logging -----------------------------------------------------------------
log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mWARN:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

require_cmd() { command -v "$1" >/dev/null 2>&1 || die "Required command not found on PATH: $1"; }

# --- staging config (secrets, never committed) -------------------------------
# .env.fly.staging holds NEON_API_KEY, NEON_PROJECT_ID, AWS_* creds, the Bugsnag
# key, and (optionally) the Auth0 M2M creds used by the smoke test. Covered by
# the *.env* gitignore rule.
STAGING_ENV_FILE="${STAGING_ENV_FILE:-$REPO_ROOT/.env.fly.staging}"

load_staging_env() {
  [ -f "$STAGING_ENV_FILE" ] || die "Missing $STAGING_ENV_FILE — see plans/2026-07-09-ephemeral-env-claude-workflow/PLAN.md (NEON_API_KEY, NEON_PROJECT_ID, AWS creds, Bugsnag key)."
  # shellcheck disable=SC1090
  source "$STAGING_ENV_FILE"
  : "${NEON_API_KEY:?NEON_API_KEY not set in $STAGING_ENV_FILE}"
  : "${NEON_PROJECT_ID:?NEON_PROJECT_ID not set in $STAGING_ENV_FILE}"
}

# --- derived names -----------------------------------------------------------
EPHEMERAL_BUCKET="${EPHEMERAL_BUCKET:-kal-ephemeral}"
STAGING_BRANCH="${STAGING_BRANCH:-staging}"
# Sibling kaleidoscope-ui checkout (overridable). Built and pushed per env in Phase 5.
FRONTEND_DIR="${FRONTEND_DIR:-$REPO_ROOT/../kaleidoscope-ui}"

# Slug: from the first argument, else the current git branch. Lowercased,
# non-alphanumerics collapsed to '-', trimmed, truncated to 20 chars.
derive_slug() {
  local raw="${1:-}"
  if [ -z "$raw" ]; then
    raw="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "eph")"
  fi
  printf '%s' "$raw" \
    | tr '[:upper:]' '[:lower:]' \
    | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//' \
    | cut -c1-20 \
    | sed -E 's/-+$//'
}

# Pull --name=<slug> out of an argument list; echoes the value (possibly empty).
parse_name_flag() {
  local name=""
  local arg
  for arg in "$@"; do
    case "$arg" in
      --name=*) name="${arg#*=}" ;;
    esac
  done
  printf '%s' "$name"
}

# Resolve the slug for a script invocation: --name flag wins, else git branch.
resolve_slug() {
  local slug
  slug="$(derive_slug "$(parse_name_flag "$@")")"
  [ -n "$slug" ] || die "Could not derive a slug — pass --name=<slug>."
  printf '%s' "$slug"
}

fly_app_name()      { printf 'kal-eph-%s' "$1"; }
neon_branch_name()  { printf 'eph-%s' "$1"; }
otel_service_name() { printf 'kal-eph-%s' "$1"; }
s3_prefix()         { printf 'eph-%s/' "$1"; }

# --- Neon helpers ------------------------------------------------------------
neon_conn_string() {
  local branch="$1"
  neonctl connection-string "$branch" \
    --project-id "$NEON_PROJECT_ID" --api-key "$NEON_API_KEY"
}

neon_branch_exists() {
  local branch="$1"
  neonctl branches get "$branch" \
    --project-id "$NEON_PROJECT_ID" --api-key "$NEON_API_KEY" >/dev/null 2>&1
}

# Parse a postgres URI (postgresql://user:pass@host[:port]/dbname?params) into
# DB_USER / DB_PASSWORD / DB_HOST / DB_NAME (exported). Port and sslmode are
# constant for Neon, so callers set those directly.
parse_conn_string() {
  local uri="$1" rest userpass hostpart dbandparams
  rest="${uri#*://}"
  userpass="${rest%%@*}"
  hostpart="${rest#*@}"
  DB_USER="${userpass%%:*}"
  DB_PASSWORD="${userpass#*:}"
  DB_HOST="${hostpart%%/*}"
  DB_HOST="${DB_HOST%%:*}"          # strip any :port
  dbandparams="${hostpart#*/}"
  DB_NAME="${dbandparams%%\?*}"
  if [ -z "$DB_HOST" ] || [ -z "$DB_NAME" ] || [ -z "$DB_USER" ]; then
    die "Could not parse Neon connection string."
  fi
  export DB_USER DB_PASSWORD DB_HOST DB_NAME
}
