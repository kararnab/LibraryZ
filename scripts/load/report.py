#!/usr/bin/env python3
"""One line per k6 run (see docs/load-testing.md): requests/s achieved,
failures, and p95 latency overall and per request kind."""
import json
import sys

summary = json.load(open(sys.argv[1]))
m = summary["metrics"]
reqs = m["http_reqs{scenario:load}"]["values"]
failed = m["http_req_failed{scenario:load}"]["values"]["rate"]
checks = m.get("checks", {}).get("values", {})
dropped = m.get("dropped_iterations", {}).get("values", {}).get("count", 0)
p95 = m["http_req_duration{scenario:load}"]["values"]["p(95)"]
# The load phase's length in seconds (argv[2]), not the whole run's: setup
# signs everyone in first.
duration = float(sys.argv[2])
kinds = {
    k.split("name:")[1].rstrip("}"): v["values"]["p(95)"]
    for k, v in m.items()
    if k.startswith("http_req_duration{name:") and v["values"].get("p(95)")
}
print(
    f"  {reqs['count'] / duration:.1f} req/s · p95 {p95:.0f} ms · failed {100 * failed:.2f}% · "
    f"checks {100 * checks.get('rate', 1):.2f}% · dropped {dropped}"
)
print("  p95 by kind: " + ", ".join(f"{k} {v:.0f}" for k, v in sorted(kinds.items(), key=lambda kv: -kv[1])))
