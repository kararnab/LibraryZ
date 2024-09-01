# LibraryZ — Compose Multiplatform client

Implements the wireframes from the Claude Design bundle (`LibraryZ Wireframes.html`)
in Kotlin / Compose Multiplatform. Targets:

- **Android** — primary, ships
- **Desktop (JVM)** — primary, ships
- **Web (Wasm/JS)** — builds, same commonMain UI in a browser
- **iOS** — targets declared; framework only builds on a **macOS** host
  (Kotlin/Native disables them automatically on Linux/Windows)

Platform-divergent work (file picker, PDF rendering, etc.) is stubbed with a
"Not yet implemented on this platform" snackbar on every target until
Phase 1.76.

## Status

- All 5 wireframe screens implemented (AuthGate, Browse, WorkDetail, Upload,
  PdfPreview).
- M3 light + dark schemes lifted verbatim from the design's `tokens.jsx`.
- Adaptive: compact (<840dp) is the phone layout; expanded (≥840dp) flips to a
  list-detail layout with a navigation rail (matches the desktop wireframe).
- Mock data, stub click handlers, snackbar host wired. **No HTTP, no real file
  picker, no actual PDF rendering yet.** Those land in the next pass once the
  Ktor client is added and per-platform helpers (PdfRenderer / PDFBox / AWT
  FileDialog) are written.

## Open in Android Studio

1. Open `frontend/` as a project (Android Studio Ladybug or later — it ships
   Kotlin 2.0+ and the Compose plugin).
2. First Gradle sync downloads the Compose Multiplatform + Android SDK
   bits; pick API 35 in the SDK Manager if not already installed.
3. Run targets:
   - **Android**: standard Run / Debug on a device or emulator.
   - **Desktop**: `./gradlew :composeApp:run`
   - **Web (Wasm)**: `./gradlew :composeApp:wasmJsBrowserDevelopmentRun` —
     serves a dev build with hot reload on `http://localhost:8080`.
     `wasmJsBrowserDistribution` produces a deployable bundle under
     `composeApp/build/dist/wasmJs/productionExecutable`.
   - **iOS**: must build on a Mac — open Xcode, add the framework produced
     by `./gradlew :composeApp:linkPodReleaseFrameworkIosArm64` (or the
     simulator variant), and host `MainViewController()` in a
     `UIViewControllerRepresentable`.

## Layout

```
frontend/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
└── composeApp/
    ├── build.gradle.kts
    └── src/
        ├── commonMain/kotlin/com/libraryz/
        │   ├── App.kt                    root composable + adaptive layout
        │   ├── theme/Theme.kt            M3 schemes from tokens.jsx
        │   ├── nav/Nav.kt                sealed Screen + tiny stack navigator
        │   ├── data/
        │   │   ├── Models.kt             Work, Edition
        │   │   └── Mock.kt               in-memory sample data
        │   └── ui/
        │       ├── components/Components.kt   WorkCard, EditionRow, EmptyState
        │       └── screens/                   5 screens
        ├── androidMain/
        │   ├── AndroidManifest.xml
        │   ├── kotlin/com/libraryz/MainActivity.kt
        │   └── res/values/themes.xml
        ├── desktopMain/kotlin/com/libraryz/Main.kt
        ├── wasmJsMain/
        │   ├── kotlin/com/libraryz/main.kt
        │   └── resources/index.html
        └── iosMain/kotlin/com/libraryz/MainViewController.kt
```

## What maps to what (design → code)

| Design artefact                       | Code                                                        |
|---------------------------------------|-------------------------------------------------------------|
| `tokens.jsx` LIGHT / DARK             | `theme/Theme.kt` (lightColorScheme / darkColorScheme)       |
| `AuthGate` (login / signup tabs)      | `ui/screens/AuthGateScreen.kt`                              |
| `Browse` populated + empty            | `ui/screens/BrowseScreen.kt`                                |
| `WorkDetail` + editions list          | `ui/screens/WorkDetailScreen.kt`                            |
| `Upload` bottom sheet / dialog        | `ui/screens/UploadScreen.kt` (ModalBottomSheet / AlertDialog)|
| `PdfPreview`                          | `ui/screens/PdfPreviewScreen.kt`                            |
| `WorkCard`, `EditionRow`, `EmptyState`| `ui/components/Components.kt`                               |
| Desktop nav rail + list-detail        | `App.kt` (ExpandedFrame + ListDetailLayout)                 |

## Next pass (not in this PR)

- Ktor client wired to `openapi/libraryz.yaml` (real signup / login / list /
  upload / download).
- Persistent JWT (DataStore on Android, file under user config dir on Desktop).
- Real `FilePicker` (`ActivityResultContracts` / `java.awt.FileDialog`).
- Real PDF rendering (Android `PdfRenderer`, Desktop `org.apache.pdfbox`).
- Replace `MockData.works` with a `WorksRepository` backed by the API.
