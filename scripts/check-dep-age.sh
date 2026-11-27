#!/usr/bin/env bash
# Checks the dependency age policy (docs/dependency-age-policy.md) for Go:
# every module version added or changed in go.mod since BASE must have been
# published at least MIN_DAYS days ago, per the Go module proxy. go.mod lists
# indirect modules too (Go ≥1.17), so this covers the whole graph. Also
# checks the `toolchain` line against the Go release's publish date.
#
#   scripts/check-dep-age.sh            # against origin/main (else HEAD)
#   BASE=HEAD~3 scripts/check-dep-age.sh
#   MIN_DAYS=7 (default)
set -euo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."

MIN_DAYS="${MIN_DAYS:-7}"
BASE="${BASE:-$(git rev-parse --verify -q origin/main >/dev/null && echo origin/main || echo HEAD)}"
PROXY="${GOPROXY_URL:-https://proxy.golang.org}"
cutoff=$(( $(date -u +%s) - MIN_DAYS * 86400 ))

# Module paths in proxy URLs escape capitals as !lower.
escape() { sed -E 's/([A-Z])/!\L\1/g' <<<"$1"; }

published() { # url -> epoch seconds, or empty
  local t
  t=$(curl -fsS "$1" 2>/dev/null | sed -n 's/.*"Time":"\([^"]*\)".*/\1/p') || true
  [ -n "$t" ] && date -u -d "$t" +%s
}

fail=0
check() { # label url
  local when; when=$(published "$2")
  if [ -z "$when" ]; then
    echo "?  $1: no publish date from the proxy"; fail=1
  elif [ "$when" -gt "$cutoff" ]; then
    echo "✗  $1: published $(date -u -d "@$when" +%F), less than $MIN_DAYS days ago"; fail=1
  else
    echo "✓  $1: published $(date -u -d "@$when" +%F)"
  fi
}

# Module versions in go.mod's require blocks that differ from BASE.
mods() { awk '/^require \(/{r=1;next} r&&/^\)/{r=0} r&&NF>=2{print $1, $2} /^require [^(]/{print $2, $3}' ; }
changed=$(comm -13 <(git show "$BASE:go.mod" | mods | sort) <(mods <go.mod | sort))
while read -r path version; do
  [ -z "$path" ] && continue
  check "$path $version" "$PROXY/$(escape "$path")/@v/$version.info"
done <<<"$changed"

old_tc=$(git show "$BASE:go.mod" | awk '/^toolchain /{print $2}')
new_tc=$(awk '/^toolchain /{print $2}' go.mod)
if [ -n "$new_tc" ] && [ "$new_tc" != "$old_tc" ]; then
  check "toolchain $new_tc" "$PROXY/golang.org/toolchain/@v/v0.0.1-$new_tc.linux-amd64.info"
fi

[ -z "$changed" ] && [ "$new_tc" = "$old_tc" ] && echo "No Go module or toolchain changes since $BASE."
exit $fail
