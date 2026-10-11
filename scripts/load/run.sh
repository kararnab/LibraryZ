#!/usr/bin/env bash
# Measures how much load a LibraryZ server handles (see docs/load-testing.md).
#
#   scripts/load/run.sh seed                 # once: start the stack, seed it, train
#   scripts/load/run.sh mix 8-9 "25 50 100"  # requests/s steps on CPUs 8-9
#   scripts/load/run.sh login 8-9 "5 10 20"  # sign-ins/s steps
#   scripts/load/run.sh idle 8-9             # memory of the idle stack
#   scripts/load/run.sh down                 # remove the stack and its volumes
#
# The stack is a separate compose project (libraryz-load) with no host
# ports; all its server containers share the CPUs given (LOAD_CPUSET). k6
# runs pinned to K6_CPUSET (default 0-7) so it doesn't compete with them.
# Results land in scripts/load/results/<cpus>/.
set -euo pipefail

cd "$(dirname "$0")/../.."
PROJECT=libraryz-load
K6_IMAGE=grafana/k6:2.3.0
K6_CPUSET="${K6_CPUSET:-0-7}"
DURATION="${DURATION:-60s}"
compose() { docker compose -p "$PROJECT" -f docker-compose.yml -f scripts/load/compose.load.yml "$@"; }

# LOAD_CORES: how many CPUs a cpuset like "8-11" or "8,9" names.
cores() { python3 -c "import sys; print(sum(int(b) - int(a) + 1 for a, _, b in (p.partition('-') if '-' in p else (p, '', p) for p in sys.argv[1].split(','))))" "$1"; }

up() {
  LOAD_CPUSET="$1" LOAD_CORES="$(cores "$1")" compose up -d --build --wait --quiet-pull >/dev/null
}

k6() {
  local out="$1"; shift
  mkdir -p "$out"
  docker run --rm --cpuset-cpus "$K6_CPUSET" --network "${PROJECT}_default" \
    -v "$PWD/scripts/load:/scripts:ro" -v "$PWD/$out:/out" \
    -e OUT=/out "$@" "$K6_IMAGE" run --quiet "/scripts/$SCRIPT"
}

# Waits for an instance to log a finished training run after a restart.
wait_trained() {
  local since="$1"
  for _ in $(seq 1 120); do
    if compose logs --since "$since" libraryz-1 libraryz-2 2>/dev/null | grep -q "rec: model retrained"; then return 0; fi
    sleep 2
  done
  echo "no training run finished within 4 minutes" >&2; return 1
}

case "${1:-}" in
  seed)
    up "${2:-8-11}"
    SCRIPT=seed.js k6 scripts/load/results/seed
    # A fresh model: instances train on start when theirs is missing or old.
    since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    compose exec -T postgres psql -U user -d libraryz -qc 'DELETE FROM rec_work_factors; DELETE FROM rec_user_factors; DELETE FROM rec_work_neighbors'
    compose restart libraryz-1 libraryz-2 >/dev/null
    wait_trained "$since"
    compose logs --since "$since" libraryz-1 libraryz-2 | grep "rec:" ;;
  mix|login)
    mode="$1" cpus="$2"
    up "$cpus"
    for rate in $3; do
      out="scripts/load/results/$cpus/$mode-$rate"
      echo "== $mode $rate/s on CPUs $cpus"
      SCRIPT=mix.js k6 "$out" -e MODE="$mode" -e RATE="$rate" -e DURATION="$DURATION" || true
      python3 scripts/load/report.py "$out/summary.json" "${DURATION%s}"
    done ;;
  idle)
    up "$2"
    sleep 30
    docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}' | grep "^$PROJECT" ;;
  down)
    compose down -v ;;
  *)
    sed -n '2,15p' "$0"; exit 1 ;;
esac
