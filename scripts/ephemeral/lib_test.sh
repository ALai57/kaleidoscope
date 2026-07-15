#!/usr/bin/env bash
# Unit tests for the slug helpers in lib.sh — no external services needed.
#
#   scripts/ephemeral/lib_test.sh
#
# Focus: derive_slug/resolve_slug must be deterministic and idempotent, since
# build-frontend and deploy-app each derive the slug independently and MUST
# agree on the S3 prefix (a mismatch pushes the SPA where the backend can't
# find it). Also covers the truncation warning and the --name-wins rule.
set -uo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

fails=0
check() { # check <description> <expected> <actual>
  if [ "$2" = "$3" ]; then
    printf '  ok: %s\n' "$1"
  else
    printf '  FAIL: %s\n    expected: %q\n    actual:   %q\n' "$1" "$2" "$3"
    fails=$((fails + 1))
  fi
}

# Normalization: lowercase, non-alnum collapsed to '-', ends trimmed.
check "lowercases and dashes non-alnum" "recipes-feature" "$(derive_slug 'Recipes/Feature')"
check "collapses runs and trims ends"   "a-b"             "$(derive_slug '  --A__B!! ')"

# Truncation at SLUG_MAX_LEN, with trailing dash trimmed.
check "truncates to the cap" "plans-recipes-feature" "$(SLUG_MAX_LEN=21 derive_slug 'plans/recipes-feature-extra' 2>/dev/null)"
check "trims dash left by the cut" "ab" "$(SLUG_MAX_LEN=3 derive_slug 'ab-cd' 2>/dev/null)"

# Idempotency: this is the invariant that keeps the frontend and backend aligned.
first="$(derive_slug 'plans/recipes-feature')"
check "derive is idempotent" "$first" "$(derive_slug "$first")"

# --name wins over everything else on the arg list.
check "resolve_slug honors --name" "recipes" "$(resolve_slug --name=recipes 2>/dev/null)"
check "resolve_slug ignores non-name args" "recipes" "$(resolve_slug foo --name=recipes bar 2>/dev/null)"

# Truncation warns on stderr (so a surprising rename is never silent).
warn_out="$(SLUG_MAX_LEN=5 derive_slug 'this-is-a-long-branch-name' 2>&1 >/dev/null)"
case "$warn_out" in
  *truncated*) printf '  ok: %s\n' "truncation warns on stderr" ;;
  *) printf '  FAIL: %s\n    stderr was: %q\n' "truncation warns on stderr" "$warn_out"; fails=$((fails + 1)) ;;
esac

# A bare resolve_slug (no --name) warns that it fell back to the git branch.
branch_warn="$(resolve_slug 2>&1 >/dev/null)"
case "$branch_warn" in
  *"No --name given"*) printf '  ok: %s\n' "branch fallback warns on stderr" ;;
  *) printf '  FAIL: %s\n    stderr was: %q\n' "branch fallback warns on stderr" "$branch_warn"; fails=$((fails + 1)) ;;
esac

# parse_ephemeral_slugs: extract kal-eph-* slugs from `fly apps list --json`.
# Only kal-eph-<slug> apps count; the trailing dash keeps unrelated names out.
apps_json='[
  {"Name":"kaleidoscope-log-shipper"},
  {"Name":"kal-eph-pr-42"},
  {"Name":"kaleidoscope"},
  {"Name":"kal-eph-recipes-feature"},
  {"Name":"kal-ephemeral"}
]'
check "parses only kal-eph-* slugs" \
  "pr-42
recipes-feature" \
  "$(printf '%s' "$apps_json" | parse_ephemeral_slugs)"

check "empty app list yields no slugs" "" "$(printf '[]' | parse_ephemeral_slugs)"

# resolve_slug_or_select: --name wins and is normalized (no fly/prompt needed).
check "resolve_slug_or_select honors --name" "recipes" "$(resolve_slug_or_select --name=Recipes 2>/dev/null)"

if [ "$fails" -eq 0 ]; then
  printf 'lib_test: all tests passed\n'
else
  printf 'lib_test: %d test(s) failed\n' "$fails"
  exit 1
fi
