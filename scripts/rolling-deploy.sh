#!/usr/bin/env bash
# Rolling deploy of the API behind Kong, one replica at a time, with no
# requests dropped:
#
#   scripts/rolling-deploy.sh            # build the image, then replace libraryz-1, libraryz-2
#   scripts/rolling-deploy.sh --no-build # replace them with the image already built
#
# For each replica: recreate it (the old container gets SIGTERM, fails /ready
# for LIBRARYZ_DRAIN_DELAY so Kong stops routing to it, finishes in-flight
# requests, then exits), wait for Docker to report the new one healthy, then
# wait for Kong to put it back in rotation before touching the next. Waiting
# on Kong matters: Docker's healthcheck and Kong's active checks run
# separately, and stopping the second replica while Kong still has the first
# marked down would leave nothing to route to.
#
# Migrations run on startup under an advisory lock, so the first new replica
# applies them while the old one keeps serving. A migration the old code
# can't run against breaks this; ship those as expand-then-contract.
set -euo pipefail

cd "$(dirname "$0")/.."

REPLICAS=(libraryz-1 libraryz-2)
UPSTREAM=libraryz-upstream
TIMEOUT=${ROLLING_DEPLOY_TIMEOUT:-180}

say() { printf '\033[1m%s\033[0m\n' "$*"; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# Kong's view of one target ("HEALTHY", "UNHEALTHY", ...), from the admin API,
# which only listens inside the Kong container. The image has no curl or
# wget, so this uses the HTTP client Kong itself ships (lua-resty-http).
kong_health() {
  docker compose exec -T kong resty -e "
    local res = require('resty.http').new():request_uri('http://127.0.0.1:8001/upstreams/$UPSTREAM/health')
    ngx.say(res and res.body or '{}')" |
    python3 -c '
import json, sys
target = sys.argv[1]
for t in json.load(sys.stdin).get("data", []):
    if t.get("target") == target:
        print(t.get("health", "UNKNOWN"))
        break
else:
    print("ABSENT")
' "$1:8080"
}

wait_for() {
  local what=$1 check=$2 deadline=$((SECONDS + TIMEOUT))
  until eval "$check"; do
    ((SECONDS < deadline)) || die "timed out after ${TIMEOUT}s waiting for $what"
    sleep 2
  done
}

if [[ ${1:-} != --no-build ]]; then
  say "Building the image"
  docker compose build "${REPLICAS[0]}"
fi

for svc in "${REPLICAS[@]}"; do
  say "Replacing $svc"
  docker compose up -d --no-deps --no-build --force-recreate "$svc"
  wait_for "$svc to be healthy" \
    "[[ \$(docker inspect -f '{{.State.Health.Status}}' \$(docker compose ps -q $svc) 2>/dev/null) == healthy ]]"
  wait_for "Kong to route to $svc" "[[ \$(kong_health $svc) == HEALTHY ]]"
  say "$svc is serving"
done
say "Deployed: ${REPLICAS[*]}"
