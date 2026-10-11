# Code-quality follow-ups (planned 2026-10-11)

These come from the review done after PR #53, which fixed nine bugs. Four
of those bugs were in library sync or merge code. The work happens on the
branch `library-sync-hardening`, **one PR**. Steps 1 and 2 are the point
of it. Steps 3 to 6 can follow in later PRs, never stacked on this one.

Throughout: write a failing test first wherever behaviour changes. Run
`go test ./...`, `go vet -tags=postgres ./...`, `desktopTest` and
`testAndroidHostTest`, plus the Wasm and iOS compiles (commands are in
CLAUDE.md). Update the test counts in CLAUDE.md (two places).

## 1. Make the in-flight library write explicit (app) — do first

**Problem.** `LibraryState`
(`frontend/composeApp/src/commonMain/kotlin/com/libraryz/data/api/LibraryState.kt`)
keeps the change being sent at `pending[0]`, guarded by a `sending` flag.
`sync()` then runs `pending.drop(1)` and assumes that is still the change
it sent. PR #53's bug #1 broke that rule. `queue()` now protects index 0
by hand, but nothing in the types enforces it.

**Design (decided):**
- Split the state into `private var inFlight: PendingWrite?` and
  `private var queued: List<PendingWrite>`, both Compose state.
- The public `pending` becomes a getter returning
  `listOfNotNull(inFlight) + queued`. The UI and tests read `pending.size`
  and `pending.isEmpty()`, so it must stay.
- `sync()`, under `lock`:
  - Move `queued.first()` into `inFlight` and remove it from `queued`.
  - After the call, act only `if (inFlight === w)`. A `forget()` (sign-out)
    during the send clears `inFlight`, and its late answer must then be
    ignored. Today that answer writes into the cleared cache.
  - **Success:** set `inFlight = null`, then
    `cache = withPending(workId, result)`, which applies the rest of
    `pending`, then `mergeIntoList` and `save()`.
  - **Transient failure:** put it back with `queued = listOf(w) + queued`
    and clear `inFlight`.
  - **Refusal:** clear `inFlight`, save, `loadEntry`, `mergeLoaded`, then
    throw (same as today).
- `queue(w)` becomes just `queued = enqueue(queued, w)`. Delete `sending`.
- `save()` stores `pending` (in-flight included), so a crash mid-send
  keeps the change. `restore()` loads it all into `queued`.
- `withPending` and `pendingNew` keep using `pending` (in-flight included).

**Tests.** The three queue tests from PR #53 must still pass
(`removalWhileAChangeIsBeingSentIsStillSent`,
`removalAChangeOfAnotherBookIsQueuedBehindKeepsThatChange`,
`theListShowsChangesStillWaitingToBeSent`). Add these:
- `forget()` while a change is in flight: its answer doesn't repopulate
  the cache or the list. Use the `CompletableDeferred`-gated `MockEngine`
  pattern from those tests.
- A transient failure of the in-flight change keeps it first, ahead of
  changes queued while it was out.
- A restart while a change is in flight: the snapshot still holds it.
  Construct a second `LibraryState` on the same `Memory` store.

## 2. One set of merge cases for Kotlin and Go

**Problem.** `applyLocally` (`data/LibraryMerge.kt`) and `mergeInto`
(`internal/library/service.go`) must agree. Only a note in CLAUDE.md
keeps them in step. They already differ on notes conflicts: the app never
sets `notesOther` for a blind edit.

**Design (decided):**
- **Fixture:** `internal/library/testdata/merge_cases.json`:
  `{"cases":[{"name", "steps":[{"put": <wire UpsertRequest JSON>}...], "want": {<wire UserBook fields>}}]}`.
  - Upserts only. Removal ordering is server-only: the app never applies
    someone else's removal, and its floor rule (`library_removals`) has no
    app-side counterpart.
  - Every group present in a `put` carries a stamp. The server treats an
    unstamped group as "now"; the app ignores it, and the app always
    stamps.
  - Use stamps like `"0000000000010.0000.a"`.
- **Compare these fields:** `status shelf current_page total_pages
  progress_percent rating notes notes_other status_stamp progress_stamp
  rating_stamp shelf_stamp notes_stamp`. A missing key, null, `""` and
  `0` all count as the same "zero": Go uses omitempty, and Kotlin skips
  default values. Skip `started_at` and `finished_at`, which are wall-clock.
- **Go:** `internal/library/merge_cases_test.go`. For each case, use a
  fresh sqlite DB with `seedWork`, `json.Unmarshal` each `put` into
  `library.UpsertRequest`, call `svc.Upsert`, marshal the result to a map,
  and compare. The helpers are in `merge_test.go`.
- **Kotlin:** add a Gradle task in `frontend/composeApp/build.gradle.kts`,
  modelled on `generateBuildInfo`. It reads
  `../../internal/library/testdata/merge_cases.json` and writes
  `internal const val MERGE_CASES_JSON = "…"` into a generated
  **commonTest** source dir. Escape `\`, `"`, `$` and newlines, and
  declare the JSON as a task input. That way desktop, Android host and
  Wasm all run the same cases without file IO. Then add
  `data/LibraryMergeCasesTest.kt`: decode with
  `Json { namingStrategy = SnakeCase; ignoreUnknownKeys = true }`, fold
  `applyLocally`, encode the `UserBook` to a `JsonObject`, and compare.
- **Parity fix found while planning:** give `applyLocally` the server's
  blind-notes rule, from `mergeInto`:
  `blind = notesBase != null && notesBase != ub.notesStamp.orEmpty() && !ub.notesStamp.isNullOrEmpty()`.
  - If the notes stamp is newer: when blind and the old notes are
    non-empty and differ, set `notesOther = old notes`; then apply the
    new notes.
  - Otherwise: when blind and the new text is non-empty and differs, set
    `notesOther = new text`.
- **Cases to include, at minimum:**
  - Newer progress wins whichever arrives first.
  - An older "read" doesn't override newer progress.
  - "read" sets 100% when its stamp is newer than progress.
  - "read" with an explicit percent keeps that percent.
  - Groups merge independently: rating from device a, shelf from device b.
  - Replaying the same put is a no-op.
  - Equal ms and counter: the device id breaks the tie.
  - A blind notes conflict in both arrival orders gives
    `notes = newer`, `notes_other = older`.
  - `resolve_notes` clears `notes_other`.
  - First touch with only a rating gives status `want`.
- Replace CLAUDE.md's "keep `applyLocally` in step with `mergeInto`" with
  "add a case to `merge_cases.json`".

## 3. Validation audit (server) — later PR

PR #53 found `POST /works` with no limits and added
`catalog.CheckWorkField`. Sweep every handler that decodes a body or query
for unbounded strings and numbers. Start with the library notes and shelf:
`UpsertRequest.validate` checks only status, progress and rating, so their
length is limited only by Kong's 5 MB body cap. Then check the sign-up name
and the rest (not checked yet). Put each package's rules next to its model,
as `CheckWorkField` does. Add a 400 test for each.

## 4. Cancellation hygiene (app) — later PR

There are 31 `catch (e: Throwable)` blocks in commonMain, and few of them
re-throw `CancellationException`. `WorksState.refreshOne` is one that
swallows it. Add a helper in `data/api/Errors.kt`, e.g.
`inline fun <T> catchingNonCancel(block: () -> T): Result<T>`, or a
`rethrowIfCancelled()` used at the top of each catch. Convert the state
holders first (`WorksState`, `LibraryState`, `RecommendationsState`,
`ContributionsState`, `AuthState`), then the screens. Test: cancelling a
`refreshOne` call stops it and doesn't leave state behind.

## 5. Split the largest files (app) — later PR, no behaviour change

| File | Lines | Split |
|---|---|---|
| `ui/screens/ReaderScreen.kt` | 1,921 | Paged view, text view and controls/sheets into their own files |
| `App.kt` | 1,394 | Navigation host, chrome frames and the screen glue in `when (top)` |
| `data/Epub.kt` | 1,079 | OPF/manifest parsing, `BookBuilder` and table layout |
| `ui/screens/UploadScreen.kt` | 955 | Separate the steps |

Do it as moves only: no logic edits in the same commit, so the diff can be
reviewed as renames.

## 6. CLAUDE.md upkeep — later, small

At 505 lines it doubles as a changelog, and the hand-kept test counts
drift (PR #53 had to update them twice). Move the dated "(2026-10-1x)"
feature narratives into PLAN.md's phase sections. Keep CLAUDE.md to
commands, conventions and gotchas. Replace exact test counts with "run X
to see the count", or keep only the date line.

## Status

- [x] 1. Explicit in-flight write
- [x] 2. Shared merge cases + notes parity
- [x] 3. Validation audit
- [x] 4. Cancellation hygiene: `rethrowIfCancelled` / `catchingNonCancel`
  in every broad catch and request `runCatching`; `LibraryState.sync` also
  requeues a send cancelled mid-flight.
- [x] 5. File splits, as moves only. Two parts stay as they were, because
  moving them would need a refactor: `Root`'s `when (top)` closes over
  Root's state, and table layout is a member of `BookBuilder`.
- [x] 6. CLAUDE.md upkeep: the narratives moved to PLAN.md Phase 7.6, and
  CLAUDE.md no longer hand-keeps test counts.
