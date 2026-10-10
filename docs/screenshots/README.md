# Screenshots

The README's screenshot grid uses the PNGs in this directory. They are
rendered from the real Compose UI (the app's own root composable, signed in
against a running backend), not captured by hand, so they can be
regenerated after any UI change.

## Regenerate

```bash
# 1. A freshly seeded instance (wipes local dev data; pre-alpha policy)
docker compose down -v && docker compose up -d --build
./scripts/seed.sh

# 2. Render every shot at 1281x796 into this directory
cd frontend
LIBRARYZ_SCREENSHOTS_DIR=$PWD/../docs/screenshots \
  ./gradlew :composeApp:desktopTest --tests '*ScreenshotsTest*' --rerun
```

`ScreenshotsTest` (frontend/composeApp/src/desktopTest) is skipped unless
`LIBRARYZ_SCREENSHOTS_DIR` is set, so CI never needs a server. Point it at
another instance with `LIBRARYZ_BASE_URL`. It signs in as the seed script's
demo users (`reader@libraryz.local` / `mod@libraryz.local`, password
`libraryz-demo`). The run takes about two minutes: it pauses between shots
to stay under Kong's 60 requests a minute per IP.

| File                | Screen                                                     |
|---------------------|------------------------------------------------------------|
| `browse.png`        | Browse + book detail (list-detail, Continue reading card)  |
| `search.png`        | Search with match highlighting, SICP open (two authors)   |
| `my_library.png`    | My Library, all statuses                                   |
| `for_you.png`       | For You recommendations                                    |
| `pdf_preview.png`   | PDF reader, two-page spread                                |
| `text_preview.png`  | Text reader, two-page spread (light reading theme)         |
| `review.png`        | Moderator review queue with a word-level description diff  |
| `login.png`         | Sign in                                                    |
| `upload_screen.png` | Add a book                                                 |
| `settings.png`      | Settings                                                   |
| `browse_dark.png`   | Dark theme, Compilers open (four authors, "by A, B and C") |

To add a shot, add a `shot(...)` line to `ScreenshotsTest` and reference
the file from the README.

## Design

The visual design (colors, type, generated covers, every screen) comes
from the "LibraryZ Visual Refresh" Claude Design canvas:
<https://claude.ai/artifact/SvHwbXULQMdr7fr2E8XiEZ>. Tokens live in
`frontend/composeApp/src/commonMain/kotlin/com/libraryz/theme/Theme.kt`.
