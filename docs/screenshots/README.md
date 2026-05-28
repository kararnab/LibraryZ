# Screenshots

The README's screenshots grid expects files in this directory. Drop PNGs
(or GIFs / WebPs — rename the references in `README.md` if you do) with
these names and they'll render automatically:

Files are named with `snake_case.png`. The README's grid references the
filenames below — keep the names in sync if you add or rename shots.

| Filename             | What it should show                                                   |
|----------------------|-----------------------------------------------------------------------|
| `browse.png`         | Browse + Work Detail (the adaptive list-detail at ≥840dp).            |
| `my_library.png`     | Personal library — a grid with mixed shelves / statuses / progress.   |
| `review.png`         | Moderator review queue with pending edits and diff cards.             |
| `pdf_preview.png`    | `PagedReader` rendering a PDF — chevron page controls, dark mat.      |
| `text_preview.png`   | `TextReader` rendering a TXT — scrolling serif body on a light surface.|
| `login.png`          | Auth gate / Log in tab — clean first-run hero.                        |
| `upload_screen.png`  | "Add edition" modal — format picker, language, file picker.           |
| `for_you.png`        | (optional) "For You" recommendations screen.                          |
| `web.png`            | (optional) The Wasm web build in a browser.                           |

## Step 1 — seed the instance

Empty screens make for bad screenshots. Bring up the stack and run the
seed script — it creates two demo users, 10 real titles (the ones from
the design bundle: Mythical Man-Month, SICP, GEB, DDIA, …), populates
the reader's library with mixed shelves, and parks 3 contributions in
the moderator queue.

```bash
docker compose up -d --build
./scripts/seed.sh

# Reader login:    reader@libraryz.local  /  libraryz-demo
# Moderator login: mod@libraryz.local     /  libraryz-demo
```

Every work gets either a real (tiny) PDF or a real TXT edition — 5 of
each. PDF previews use the platform-native rasterizers (PDFBox / pdf.js /
PdfRenderer / PDFKit) through `PagedReader`; TXT previews go through
`TextReader` and render with native Compose `Text` (selectable, reflowing,
no rasterization). Both look good in screenshots.

Suggested preview shots:

- `pdf-preview.png` — open any of: Mythical Man-Month, SICP, GEB,
  Compilers, DDIA. Dark mat background, chevron page controls.
- `text-preview.png` — open any of: A Pattern Language, Refactoring,
  Code Complete, TAOCP, Pragmatic Programmer. Light surface, scrolling
  serif text, no chevrons.

## Step 2 — design system to match

The implementation uses Material 3 with the palette from the design
bundle (`design/tokens.jsx` in the source bundle, mirrored in the app):

| Token            | Light       | Dark        |
|------------------|-------------|-------------|
| primary          | `#6750A4`   | `#D0BCFF`   |
| primaryContainer | `#EADDFF`   | `#4F378B`   |
| surface          | `#FEF7FF`   | `#141218`   |
| onSurface        | `#1D1B20`   | `#E6E0E9`   |
| outlineVariant   | `#CAC4D0`   | `#49454F`   |

Typography is **Roboto** (Roboto Mono for monospaced bits).

**Capture in light mode** unless you have a strong reason not to —
README screenshots get read on a white background most of the time and
the `#FEF7FF` surface tone reads cleanly there.

## Step 3 — pick the right form factor per screen

The app is adaptive at the **840dp** breakpoint:

- **Compact (< 840dp)** — phone single-pane with a bottom-nav-ish flow.
  Use this for `browse.png`, `my-library.png`, and `for-you.png`.
- **Expanded (≥ 840dp)** — navigation rail on the left + list-detail
  layout. Use this for `work-detail.png` (it looks much richer than the
  compact stacked variant) and `contributions.png` (the moderator queue
  list-detail is the design's hero screen for Phase 2).

So a good capture mix is:

- 3 phone-frame shots: `browse`, `my-library`, `for-you`
- 2 desktop shots: `work-detail`, `contributions`
- 1 browser shot: `web` (Wasm in Chrome/Firefox, clean tab, no extensions)

That gives the README visual variety and demonstrates the adaptive
layout at the same time.

## Step 4 — capture tips

- **Desktop** — `./gradlew :composeApp:run` opens a JVM window. Resize
  to ~1280×800 before capturing; the list-detail expands cleanly at
  that size. Use your OS screenshot tool to grab the window with no OS
  chrome (macOS: `Cmd+Shift+4` then `Space`; GNOME: `Alt+PrintScreen`).
- **Android** — run on an emulator (Pixel 7 / API 34 looks good) and
  use `adb exec-out screencap -p > shot.png` from a host shell, or the
  emulator's built-in screenshot button. Phone frame is optional — one
  framed shot among unframed ones tends to look uneven.
- **Web** — `./gradlew :composeApp:wasmJsBrowserDevelopmentRun` then
  capture the browser tab. Hide bookmarks bar first; consider a clean
  guest profile.
- **Crop tightly.** Trim window chrome unless it's load-bearing.
- **Keep file sizes reasonable** — under ~400 KB per PNG. `pngquant
  --quality=70-90` or `oxipng -o4` are fine and lossless-enough.

## Animated demos

A 5–10 second loop is the highest-leverage thing you can add. Suggested
script:

1. Browse → open a work
2. Open Upload sheet → pick file → submit
3. New edition appears in the work detail
4. Switch to "For You" → see recommendations

Record with [`vhs`](https://github.com/charmbracelet/vhs) for terminal
work, [`peek`](https://github.com/phw/peek) on Linux, or the built-in
screen recorder on macOS / Windows. Save as `demo.gif` here and add a
"Demo" heading at the top of the main README.
