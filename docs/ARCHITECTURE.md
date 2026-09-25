# PDF Viewer — Architecture & Plan

An ad-free, tracker-free, offline PDF reader and toolkit for Android, published on Google Play.

## Principles

1. **No ads, no analytics, no network.** The manifest has no `INTERNET` permission. That is a
   feature, and the store listing says so.
2. **No broad storage permissions.** Files come in through the Storage Access Framework
   (file picker, folder picker), "Open with" and share intents. No `MANAGE_EXTERNAL_STORAGE`.
3. **Non-destructive.** The original PDF is never modified in place. Annotations are stored in
   the app until the user exports an annotated copy. Tools always write new files.
4. **This repository is public.** Never commit keystores, passwords, service-account JSON,
   `local.properties` or personal data. Signing happens only through CI secrets or an
   untracked `keystore.properties`.

## Tech stack

| Area | Choice | License |
|---|---|---|
| Language/UI | Kotlin, Jetpack Compose, Material 3 | Apache-2.0 |
| Rendering, text, search, links, TOC | Pdfium via `io.legere:pdfiumandroid` | Apache-2.0 / BSD |
| Writing PDFs (annotations, merge, split…) | `com.tom-roush:pdfbox-android` | Apache-2.0 |
| Persistence | Room (library, bookmarks, annotations), DataStore (settings) | Apache-2.0 |
| Navigation | Navigation Compose with type-safe `@Serializable` routes | Apache-2.0 |
| DI | Manual `AppContainer` (no framework) | — |

Build: AGP 9 (built-in Kotlin), Gradle 9, compileSdk 37, targetSdk 36, minSdk 26, JDK 17 bytecode.
The build is verified by GitHub Actions (`.github/workflows/ci.yml`).

## Package layout (`io.github.tffy1.pdfviewer`)

```
PdfViewerApp, AppContainer, MainActivity        app entry + manual DI           [scaffold]
navigation/                                     routes + NavHost + bottom bar   [scaffold]
core/model/Geometry.kt                          PageSize/PageRect/PagePoint     [scaffold]
pdf/PdfEngine.kt                                engine contract                 [scaffold]
pdf/pdfium/                                     Pdfium implementation           [agent 1]
data/                                           Room + DataStore + repositories [scaffold]
io/DocumentAccess.kt                            URI → descriptor / info         [scaffold → agent 10]
ui/viewer/document/                             zoom/scroll/tiling view         [agent 2]
ui/viewer/ (ViewerScreen, ViewModel, chrome)    viewer screen & integration     [agent 3]
ui/viewer/search/, selection/, links/           search, text select, links      [agent 4]
ui/viewer/annotations/                          annotate + export via PdfBox    [agent 5]
ui/library/                                     home: recents/favorites/folders [agent 6]
tools/, ui/tools/                               merge/split/rotate/etc.         [agent 7]
ui/theme/, ui/settings/, ui/about/, icons       look & feel, settings, licenses [agent 8]
.github/, fastlane/, docs/, proguard            CI/release/Play Store readiness [agent 9]
integration/                                    intents, print, share, save-as  [agent 10]
```

## Coordinate system

Everything geometric uses **page space**: PDF points (1/72"), origin at the top-left of the page as
displayed (rotation applied), y down. The engine converts from PDF user space; UI converts page
space to pixels with `PageLayoutInfo.scale`.

## Feature scope (v1)

- **Open**: file picker, "Open with", share-to, recents, library folders; password-protected PDFs.
- **Read**: continuous vertical scroll or horizontal paging; pinch and double-tap zoom with sharp
  high-res tiles; page indicator and scrubber; go to page; outline/TOC; thumbnails grid; internal
  and external links (external links ask for confirmation); night/sepia modes; keep screen on;
  fullscreen; resume at the last page; volume-key paging.
- **Find**: full-text search with highlighted results, next/previous.
- **Select**: long-press text selection with handles, copy, share.
- **Annotate**: highlight/underline/strikethrough from a selection, freehand ink, sticky notes,
  eraser and undo; export an annotated copy (real PDF annotations).
- **Bookmarks**: per-document bookmarks.
- **Tools**: merge, split, extract pages, rotate/reorder/delete pages, images → PDF, remove password
  (with the known password), compress.
- **Share and print**: share, print through the Android print framework, save a copy, document properties.
- **Settings**: theme (system/light/dark), dynamic color, default scroll mode, reading mode, etc.
- **About**: version, privacy statement, open-source licenses.

## Rules for contributors (and agents)

- Each feature keeps its strings in `res/values/strings_<feature>.xml`. Shared ones live in `strings.xml`.
- Only the scaffold owner edits `app/build.gradle.kts`, `gradle/libs.versions.toml`, `AppContainer`,
  `navigation/` and `data/`. Anyone needing a change asks for it in their hand-off notes.
- Contract files (marked `CONTRACT`) keep their public signatures source-compatible.
- Heavy work stays off the main thread. The engine serializes all native Pdfium calls.
- Pure logic goes in plain Kotlin with unit tests under `app/src/test`.
