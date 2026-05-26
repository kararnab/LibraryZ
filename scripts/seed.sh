#!/usr/bin/env bash
#
# Seed a LibraryZ instance with demo data so screenshots / screencasts look
# populated.
#
# This script is Kong-aware. When `docker compose up` is used, Kong sits at
# the edge and rate-limits per-IP:
#
#   POST /auth/login              5/min, 30/hour
#   POST /auth/signup             3/min, 10/hour
#   POST /works/{id}/editions    10/hour
#
# The seed creates 10 works (= 10 edition uploads), exactly the hour budget.
# So this script is structured to be **idempotent** — re-running it skips
# anything that already exists, and it caches JWTs to /tmp so re-runs don't
# burn the login bucket either.
#
# Requirements:
#   - curl
#   - jq
#   - A running backend (default: http://localhost:8080)
#   - For the moderator promotion step, either `docker compose` or
#     a `psql` with $DATABASE_URL set.
#
# Usage:
#   ./scripts/seed.sh                           # defaults
#   BASE=http://localhost:8080 ./scripts/seed.sh
#   ./scripts/seed.sh --no-moderator            # skip moderator promotion
#   ./scripts/seed.sh --reset                   # drop cached tokens first
#
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
DO_MOD=1
RESET=0
for arg in "$@"; do
  case "$arg" in
    --no-moderator) DO_MOD=0 ;;
    --reset)        RESET=1 ;;
    -h|--help)
      sed -n '2,28p' "$0"; exit 0 ;;
  esac
done

for bin in curl jq; do
  command -v "$bin" >/dev/null || { echo "error: $bin not on PATH" >&2; exit 1; }
done

say()  { printf '\033[1;35m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# Per-host token cache, so re-runs don't burn the login rate limit.
CACHE_HOST=$(printf '%s' "$BASE" | tr -c '[:alnum:]' '_')
CACHE_FILE="${TMPDIR:-/tmp}/libraryz-seed-tokens-$CACHE_HOST.json"
[ "$RESET" = "1" ] && rm -f "$CACHE_FILE"

# ── Smoke check ────────────────────────────────────────────────────────────
say "Pinging $BASE"
health=$(curl -si "$BASE/health" || true)
echo "$health" | grep -q '^HTTP.* 200' || die "backend not reachable at $BASE — start it first"
behind_kong=0
if echo "$health" | grep -qi '^server: kong'; then
  behind_kong=1
  ok "backend up (behind Kong — rate limits apply)"
else
  ok "backend up"
fi

# ── HTTP helper that surfaces failures ─────────────────────────────────────
# Calls curl, prints status + body on non-2xx, prints the body on success.
# Usage:   api METHOD PATH [extra curl args...]
api() {
  local method="$1" path="$2"; shift 2
  local tmp; tmp=$(mktemp)
  local code
  code=$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" "$BASE$path" "$@" || echo "000")
  if [ "$code" -ge 200 ] && [ "$code" -lt 300 ]; then
    cat "$tmp"; rm -f "$tmp"; return 0
  fi
  local body; body=$(cat "$tmp"); rm -f "$tmp"
  if [ "$code" = "429" ]; then
    warn "$method $path → 429 (rate-limited by Kong). Body: $body"
    if [ "$behind_kong" = "1" ]; then
      warn "  Kong limits: login 5/min · signup 3/min · edition upload 10/hour"
      warn "  Wait for the bucket to refill, then re-run with --reset to retry."
    fi
  else
    warn "$method $path → HTTP $code. Body: $body"
  fi
  return 1
}

# ── Users ──────────────────────────────────────────────────────────────────
MOD_EMAIL="mod@libraryz.local"
USER_EMAIL="reader@libraryz.local"
PASS="libraryz-demo"

signup_idempotent() {
  local email="$1" name="$2"
  local tmp; tmp=$(mktemp)
  local code
  code=$(curl -sS -o "$tmp" -w '%{http_code}' -X POST "$BASE/auth/signup" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n --arg e "$email" --arg p "$PASS" --arg n "$name" \
              '{email:$e, password:$p, name:$n}')" || echo "000")
  rm -f "$tmp"
  case "$code" in
    201)         ok "signup: $email (created)" ;;
    409)         ok "signup: $email (already exists, reusing)" ;;
    429)         warn "signup: $email → 429 rate-limited"; return 1 ;;
    *)           warn "signup: $email → HTTP $code"; return 1 ;;
  esac
}

login_token() {
  local email="$1"
  local tmp; tmp=$(mktemp); local hdr; hdr=$(mktemp)
  local code
  code=$(curl -sS -o "$tmp" -D "$hdr" -w '%{http_code}' -X POST "$BASE/auth/login" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n --arg e "$email" --arg p "$PASS" '{email:$e, password:$p}')" \
    || echo "000")
  if [ "$code" != "200" ]; then
    local body; body=$(cat "$tmp")
    rm -f "$tmp" "$hdr"
    if [ "$code" = "429" ]; then
      warn "login $email → 429 rate-limited (Kong: 5/min, 30/hour per IP)"
      warn "  cached tokens at $CACHE_FILE are reused on re-runs — re-run as-is in a minute"
    else
      warn "login $email → HTTP $code · $body"
    fi
    return 1
  fi
  # Extract the Bearer token from the response Authorization header.
  # Use character classes for case-insensitivity (works in mawk + gawk +
  # busybox awk — IGNORECASE is a gawk-only extension).
  local token
  token=$(awk '/^[Aa]uthorization:[[:space:]]*[Bb]earer[[:space:]]+/ {
                 sub(/^[Aa]uthorization:[[:space:]]*[Bb]earer[[:space:]]+/, "");
                 sub(/[\r\n]+$/, ""); print; exit }' "$hdr")
  rm -f "$tmp" "$hdr"
  if [ -z "$token" ]; then
    warn "login $email → 200 but no Authorization header in response"
    return 1
  fi
  printf '%s\n' "$token"
}

# Load cached tokens if present and still valid (cheap probe: GET /auth/me).
probe() { curl -sS -o /dev/null -w '%{http_code}' "$BASE/auth/me" -H "Authorization: Bearer $1"; }

MOD_TOKEN=""; USER_TOKEN=""
if [ -f "$CACHE_FILE" ]; then
  MOD_TOKEN=$(jq -r '.mod   // empty' "$CACHE_FILE" 2>/dev/null || true)
  USER_TOKEN=$(jq -r '.user // empty' "$CACHE_FILE" 2>/dev/null || true)
  [ -n "$MOD_TOKEN" ]  && [ "$(probe "$MOD_TOKEN")"  = "200" ] || MOD_TOKEN=""
  [ -n "$USER_TOKEN" ] && [ "$(probe "$USER_TOKEN")" = "200" ] || USER_TOKEN=""
  [ -n "$MOD_TOKEN" ]  && ok "reusing cached moderator token"
  [ -n "$USER_TOKEN" ] && ok "reusing cached reader token"
fi

if [ -z "$MOD_TOKEN" ] || [ -z "$USER_TOKEN" ]; then
  say "Creating demo users"
  signup_idempotent "$MOD_EMAIL"  "Demo Moderator" || true
  signup_idempotent "$USER_EMAIL" "Demo Reader"    || true

  say "Logging in"
  [ -z "$MOD_TOKEN" ]  && MOD_TOKEN=$(login_token "$MOD_EMAIL")  || true
  [ -z "$USER_TOKEN" ] && USER_TOKEN=$(login_token "$USER_EMAIL") || true
  [ -n "$MOD_TOKEN" ]  || die "moderator login failed (see message above)"
  [ -n "$USER_TOKEN" ] || die "reader login failed (see message above)"

  jq -n --arg m "$MOD_TOKEN" --arg u "$USER_TOKEN" \
        '{mod:$m, user:$u}' > "$CACHE_FILE"
  ok "tokens cached at $CACHE_FILE"
fi

# ── Works (titles match the design bundle's sample data) ───────────────────
say "Creating works (skipping any that already exist)"

# Fetch existing titles once to avoid re-creating.
EXISTING=$(curl -sS "$BASE/works?limit=200" | jq -r '.[]?.title // empty' || true)
title_exists() { printf '%s\n' "$EXISTING" | grep -Fxq -- "$1"; }
id_for_title() {
  curl -sS "$BASE/works?limit=200" \
    | jq -r --arg t "$1" '.[] | select(.title == $t) | .id' | head -n1
}

# title|authors|year|language|isbn|description|format
# Format mix is 5 PDF + 5 TXT so the new TextReader has things to render
# and the PdfBackend path still has coverage. Total uploads = 10, right at
# Kong's per-IP /works/{id}/editions bucket of 10/hour.
WORKS=(
"The Mythical Man-Month|Frederick P. Brooks Jr.|1975|en|9780201835953|Essays on software engineering, including Brooks's law — adding people to a late software project makes it later.|PDF"
"Structure and Interpretation of Computer Programs|Harold Abelson; Gerald Jay Sussman|1985|en|9780262510875|The classic MIT introduction to computer science, using Scheme. Often called the wizard book.|PDF"
"A Pattern Language|Christopher Alexander; Sara Ishikawa; Murray Silverstein|1977|en|9780195019193|253 architectural patterns for living spaces, towns, and buildings — a foundational influence on software design patterns.|TXT"
"Gödel, Escher, Bach|Douglas Hofstadter|1979|en|9780465026562|A Pulitzer-winning meditation on minds, machines, and self-reference, woven through Gödel's incompleteness, Escher's prints, and Bach's fugues.|PDF"
"The Art of Computer Programming, Vol. 1|Donald E. Knuth|1968|en|9780201896831|Volume one — Fundamental Algorithms. The reference.|TXT"
"Compilers: Principles, Techniques, and Tools|Alfred V. Aho; Monica S. Lam; Ravi Sethi; Jeffrey D. Ullman|1986|en|9780321486813|The Dragon Book. Lexing, parsing, semantic analysis, code generation, optimization.|PDF"
"Refactoring|Martin Fowler|1999|en|9780201485677|A catalog of safe transformations for improving the design of existing code.|TXT"
"Designing Data-Intensive Applications|Martin Kleppmann|2017|en|9781449373320|Reliability, scalability, and maintainability for systems that move, store, and process data.|PDF"
"The Pragmatic Programmer|Andrew Hunt; David Thomas|1999|en|9780201616224|Pragmatism, orthogonality, DRY, and a hundred other tips for everyday software work.|TXT"
"Code Complete|Steve McConnell|2004|en|9780735619678|A practical handbook of software construction — naming, layout, defensive programming, the works.|TXT"
)

PLACEHOLDER_DIR="$(mktemp -d)"
trap 'rm -rf "$PLACEHOLDER_DIR"' EXIT

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PDF_BUILDER="$SCRIPT_DIR/seed-pdf.py"
HAVE_PYTHON=0
if command -v python3 >/dev/null && [ -x "$PDF_BUILDER" ]; then
  HAVE_PYTHON=1
else
  warn "python3 not found — seeded editions will be text placeholders that"
  warn "  the PDF preview can't render. Upload a real PDF through the UI"
  warn "  for the pdf-preview screenshot."
fi

# True iff the given work already has an edition of this format. Lets the
# script add a missing TXT edition to a work that was previously seeded
# with only a PDF, without re-uploading the PDF.
work_has_edition_format() {
  local work_id="$1" format="$2"
  curl -sS "$BASE/works/$work_id" \
    | jq -e --arg f "$format" '.editions // [] | map(.format) | any(. == $f)' \
    >/dev/null 2>&1
}

# Inline TXT generator — short demo excerpt. Honest placeholder content,
# clearly marked. Reuses the description from the WORKS row so each file
# is distinct (different sha256, no dedup collision).
write_txt_edition() {
  local out="$1" title="$2" authors="$3" year="$4" desc="$5"
  # Paragraphs are unwrapped (one long line each, separated by blank lines)
  # so Compose's word-wrap reflows them to the viewport. If you hard-wrap
  # the source here, the reader can't widen the column on a desktop window.
  cat >"$out" <<EOF
$title

$authors ($year)

═══════════════════════════════════════════════════════════════════════

About this edition

This is a demo text edition created by the LibraryZ seed script. It exists so the TextReader has something to render in screenshots and demos. Replace it with the real text when you wire in a content pipeline — Project Gutenberg dumps, scanned-OCR output, your own uploads.

About the book

$desc

═══════════════════════════════════════════════════════════════════════

This edition is served as plain UTF-8 text. LibraryZ's TextReader decodes it once with bytes.decodeToString() and renders it with Compose Text — no rasterization, reflows for free at any window width, and remains selectable and accessible. Compare with the PDF editions on other works (e.g. The Mythical Man-Month, SICP), which go through the PagedReader path: PDFBox on Desktop, PdfRenderer on Android, pdf.js on Web, PDFKit on iOS.

The pagination model is also different: PDFs have prev/next chevrons in the top bar driven by the document's fixed page count; text scrolls naturally because there's no inherent "page" in a flowing text stream until a viewport is involved.

[End of demo content. ~1.6 KB.]
EOF
}

WORK_IDS=()
uploads_attempted=0
uploads_skipped=0
i=0
for row in "${WORKS[@]}"; do
  IFS='|' read -r title authors year lang isbn desc format <<<"$row"
  format="${format:-PDF}"  # default if a row was authored without the trailing field
  i=$((i+1))

  # 1) Create work (idempotent by title).
  if title_exists "$title"; then
    wid=$(id_for_title "$title")
  else
    payload=$(jq -n \
      --arg t "$title" --arg a "$authors" --arg d "$desc" \
      --arg l "$lang" --arg isbn "$isbn" --argjson y "$year" \
      '{title:$t, authors:$a, description:$d, language:$l, isbn:$isbn, publication_year:$y}')
    resp=$(api POST /works \
      -H "Authorization: Bearer $USER_TOKEN" \
      -H 'Content-Type: application/json' \
      -d "$payload") || die "create work failed at $i/$title"
    wid=$(echo "$resp" | jq -r '.id')
  fi
  WORK_IDS+=("$wid")

  # 2) Upload an edition in the target format, but only if the work doesn't
  #    already have one — saves the Kong upload bucket on re-runs.
  if work_has_edition_format "$wid" "$format"; then
    ok "[$i/${#WORKS[@]}] $title — $format edition already exists, skipping"
    uploads_skipped=$((uploads_skipped+1))
    continue
  fi

  stub="$PLACEHOLDER_DIR/work-$i"
  case "$format" in
    PDF)
      stub="$stub.pdf"
      if [ "$HAVE_PYTHON" = "1" ]; then
        python3 "$PDF_BUILDER" "$title" "$authors" "$stub"
      else
        printf 'LibraryZ demo placeholder #%d - %s\n' "$i" "$title" > "$stub"
        warn "  $title: python3 unavailable — wrote text bytes with .pdf extension; preview won't render"
      fi ;;
    TXT)
      stub="$stub.txt"
      write_txt_edition "$stub" "$title" "$authors" "$year" "$desc" ;;
    *)
      warn "  $title: unknown format '$format' in WORKS row; skipping"
      continue ;;
  esac

  uploads_attempted=$((uploads_attempted+1))
  if ! api POST "/works/$wid/editions" \
        -H "Authorization: Bearer $USER_TOKEN" \
        -F format="$format" -F language="$lang" -F file="@$stub" >/dev/null; then
    warn "  edition upload failed for $title (Kong upload bucket may be exhausted — 10/hour)"
    warn "  the work was created without an edition; rerun the script in an hour to attach one"
    continue
  fi
  ok "[$i/${#WORKS[@]}] $title ($format edition uploaded)"
done

# ── Personal library entries ───────────────────────────────────────────────
say "Populating reader's personal library"

set_library() {
  local work_id="$1" body="$2" title_for_log="$3"
  api PUT "/me/library/$work_id" \
    -H "Authorization: Bearer $USER_TOKEN" \
    -H 'Content-Type: application/json' \
    -d "$body" >/dev/null && ok "  shelf entry: $title_for_log" \
    || warn "  shelf entry failed for $title_for_log"
}

# Pair each row above with a shelf/status/rating for a varied grid.
LIB=(
  '0|{"status":"read","rating":5,"shelf":"classics"}'
  '1|{"status":"reading","progress_percent":42,"shelf":"in-progress"}'
  '2|{"status":"want","shelf":"to-read"}'
  '3|{"status":"read","rating":5,"shelf":"classics"}'
  '4|{"status":"want","shelf":"reference"}'
  '5|{"status":"reading","progress_percent":18,"shelf":"in-progress"}'
  '6|{"status":"read","rating":4,"shelf":"work"}'
  '7|{"status":"reading","progress_percent":67,"shelf":"in-progress"}'
)
for row in "${LIB[@]}"; do
  idx="${row%%|*}"; body="${row#*|}"
  if [ -n "${WORK_IDS[$idx]:-}" ]; then
    title=$(printf '%s\n' "${WORKS[$idx]}" | cut -d'|' -f1)
    set_library "${WORK_IDS[$idx]}" "$body" "$title"
  fi
done

# ── Contributions in the moderator queue ───────────────────────────────────
say "Submitting contributions"

submit_contrib() {
  local work_id="$1" patch="$2" label="$3"
  api POST "/works/$work_id/contributions" \
    -H "Authorization: Bearer $USER_TOKEN" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n --argjson p "$patch" '{patch:$p}')" >/dev/null \
    && ok "  $label" || warn "  contribution failed: $label"
}

[ -n "${WORK_IDS[1]:-}" ] && submit_contrib "${WORK_IDS[1]}" \
  '{"subtitle":"The Wizard Book"}' \
  "add subtitle to SICP"
[ -n "${WORK_IDS[4]:-}" ] && submit_contrib "${WORK_IDS[4]}" \
  '{"publication_year":1997}' \
  "fix publication year on TAOCP"
[ -n "${WORK_IDS[6]:-}" ] && submit_contrib "${WORK_IDS[6]}" \
  '{"description":"A catalog of safe, behavior-preserving transformations for improving the design of existing code. Second edition uses JavaScript examples."}' \
  "tweak description on Refactoring"

# ── Moderator promotion ───────────────────────────────────────────────────
if [ "$DO_MOD" = "1" ]; then
  say "Promoting $MOD_EMAIL to moderator"
  SQL="UPDATE users SET is_moderator = true WHERE email = '$MOD_EMAIL';"
  if docker compose ps postgres 2>/dev/null | grep -qE 'Up|running'; then
    docker compose exec -T postgres psql -U user -d libraryz -c "$SQL" >/dev/null
    ok "promoted via docker compose"
  elif command -v psql >/dev/null && [ -n "${DATABASE_URL:-}" ]; then
    psql "$DATABASE_URL" -c "$SQL" >/dev/null
    ok "promoted via \$DATABASE_URL"
  else
    warn "couldn't auto-promote — run this yourself:"
    warn "  docker compose exec postgres psql -U user -d libraryz -c \"$SQL\""
  fi
fi

# ── Summary ────────────────────────────────────────────────────────────────
cat <<EOF

────────────────────────────────────────────────────────────
  Seed complete.

  Reader login:     $USER_EMAIL  /  $PASS
  Moderator login:  $MOD_EMAIL  /  $PASS

  ${#WORKS[@]} works · uploads attempted: $uploads_attempted · skipped (already existed): $uploads_skipped
  Format mix: 5 PDF + 5 TXT editions (Reader covers both paths).

  Now open the client and grab screenshots:
    cd frontend && JAVA_HOME=/path/to/jdk-21 ./gradlew :composeApp:run

  For preview screenshots: open any PDF-bearing work (Mythical Man-Month,
  SICP, GEB, Compilers, DDIA) for the paged reader; open any TXT-bearing
  work (A Pattern Language, Refactoring, Code Complete, TAOCP,
  Pragmatic Programmer) for the new text reader.

  Tokens cached at $CACHE_FILE (delete or pass --reset to refresh).
────────────────────────────────────────────────────────────
EOF
