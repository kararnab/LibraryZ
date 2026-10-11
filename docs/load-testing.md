# Load testing

How much a LibraryZ server can take, how it was measured, and how to measure
your own. The short version is in the README
([How big a server do you need?](../README.md#how-big-a-server-do-you-need)).

## Results (2026-10-11)

Measured on one desktop, with the server limited to 1, 2 or 4 CPU cores (see
[How it was measured](#how-it-was-measured)). These are measurements of
the API itself: the load went to the two API instances directly, not through
Kong.

| Cores | Reading traffic, comfortable | Saturates at | Sign-ins / s | Idle memory, whole stack |
|---:|---|---|---|---|
| 1 | 400 req/s, p95 11 ms | 400–450 req/s | 40 (p95 20 ms) | ~320 MB |
| 2 | 800 req/s, p95 16 ms | 800–1,000 req/s | 80 (p95 25 ms) | ~385 MB |
| 4 | 1,400 req/s, p95 19 ms | 1,400–1,600 req/s | 160 (p95 28 ms) | ~505 MB |

"Saturates" is the range between the last step that kept up and the
first that didn't: there the server stops keeping up: requests queue and the
p95 jumps from milliseconds to seconds. There was no gradual slowdown
before it. Even saturated, under 0.1% of requests failed; the rest waited.

**In people:** a reader's app sends about two requests a minute (a position
save a second after each page turn, plus opening books and lists). At half
the comfortable rate, to leave headroom, one core serves about **6,000
people reading at the same time**, two cores about 12,000, four about
20,000. For a book club, a lab or a school, the API is not what limits you;
see [What does limit a small server](#what-does-limit-a-small-server).

**Sign-ins** cost far more than reading: passwords are hashed with argon2id
(19 MiB and about 25 ms of one core each), so each core handles about 40 a
second. Sessions last 30 days and refresh without a password, so this
matters when a whole class signs in at once, not day to day.

**Idle memory** by container (2 cores): Kong 181 MB, RustFS 108 MB, the two
API instances 31 MB each, Postgres 23 MB, Redis 9 MB, pgbouncer 2 MB. Kong
runs one nginx worker per CPU, so it grows with cores (114 MB on 1, 312 MB
on 4).

### Latency by request at the comfortable rate

p95 in milliseconds:

| Request | 1 core @ 400/s | 2 cores @ 800/s | 4 cores @ 1,400/s |
|---|---:|---:|---:|
| For You (`GET /me/recommendations`) | 26 | 39 | 50 |
| Save position (`PUT /me/library/{id}`) | 10 | 13 | 13 |
| My Library (`GET /me/library`) | 10 | 13 | 12 |
| Download an 80 KB book | 8 | 10 | 9 |
| Catalogue page (`GET /works`) | 7 | 9 | 8 |
| Book page (`GET /works/{id}`) | 5 | 8 | 8 |
| Search | 5 | 7 | 7 |
| Open a book (`GET /me/library/{id}`) | 5 | 7 | 8 |

For You is the slowest because it scores the reader against the trained
model on every request.

## What does limit a small server

- **Memory for the PDF safety check.** Each uploaded PDF is checked in a
  separate process allowed up to `LIBRARYZ_PDF_SANITIZE_MEMORY_MB` (1 GB),
  and `LIBRARYZ_PDF_SANITIZE_CONCURRENCY` (2) run at once. With the defaults,
  keep 2 GB free beyond the ~0.5 GB idle stack: **4 GB is comfortable**. On
  1–2 GB, set concurrency to 1 and the budget to 512 MB; a PDF that needs
  more is then refused, not the server.
- **Upload bandwidth for downloads.** Books stream through the API, so
  your connection's upload speed decides how fast readers get them: a 5 MB
  EPUB takes about half a second at 100 Mbit/s.
- **Kong's per-IP limits.** Kong allows **60 requests a minute per IP**
  across the API, and less for sign-in, sign-up and upload
  ([deploy/kong/kong.yml](../deploy/kong/kong.yml)). Everyone behind one
  public address (a school or office network) shares that allowance, and
  30 people reading at two requests a minute each use it up. For such a
  group, raise the `minute` limits in `kong.yml`. Per-account limits
  (sign-in throttling, upload quotas) are in the app and aren't affected.
- **Recommendation training** retrains every 6 hours
  (`LIBRARYZ_REC_RETRAIN_INTERVAL`) on one instance. With 300 readers and
  6,000 shelf entries it took under a second; it grows with readers × books.

## How it was measured

- **Machine:** Intel Core Ultra 7 265K, 30 GB RAM, NVMe, Linux. Every server
  container (both API instances, Postgres, pgbouncer, Redis, RustFS, Kong) was
  pinned to the same 1, 2 or 4 of its *efficiency* cores (up to 4.6 GHz),
  so they competed for CPU as on a small machine. Kong's nginx workers were
  set to the same number. k6 ran on the other cores.
- **Not a Raspberry Pi or a VPS.** A cloud vCPU or a Pi core is usually
  slower than this one; a Pi 5 core may be two to three times slower.
  Scale the request rates down to match. Even a third of the 1-core figure
  is far more than a small group needs, and memory is the same everywhere.
- **Data:** 300 accounts, 500 books (50 with an 80 KB text edition), 20
  shelf entries per account (40% rated), and a model trained on them. A
  much larger catalogue will make search and the catalogue pages slower.
  That wasn't measured.
- **Traffic:** a constant arrival rate for 60 s per step (30 s for
  sign-ins), each request one of these, by weight: save position 50,
  book page 10, My Library 10, catalogue page 10, search 8, open a book 5,
  For You 5, download 2. Requests are spread over the two API instances,
  as Kong spreads them.
- **What isn't covered:** uploads and the PDF check (memory-bound, see
  above), Kong's own overhead per request, and Postgres with a working set
  larger than its memory.

## Run it yourself

You need Docker and Python 3. Nothing is installed on the host: k6 runs
from its image.

```bash
scripts/load/run.sh seed                      # once: start the stack, seed it, train
scripts/load/run.sh mix 8-9 "200 400 800"     # requests/s steps on CPUs 8-9
scripts/load/run.sh login 8-9 "20 40 80"      # sign-ins/s steps
scripts/load/run.sh idle 8-9                  # idle memory per container
scripts/load/run.sh down                      # remove the stack and its volumes
```

The stack is a separate compose project, `libraryz-load`, with no host
ports and its own volumes, so your dev stack and its data are untouched. The
CPU list is a Docker `cpuset`; pick cores that exist on your machine, and
point `K6_CPUSET` (default `0-7`) at different ones so k6 doesn't compete with
the server. Each step prints the achieved rate, failures and p95 by request;
the full k6 summaries go to `scripts/load/results/` (not committed).
Sizes are set with `USERS`, `WORKS`, `EDITIONS` and `PER_USER` in
[seed.js](../scripts/load/seed.js), and the request mix is at the top of
[mix.js](../scripts/load/mix.js).
