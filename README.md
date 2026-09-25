# PDF Viewer

[![CI](https://github.com/TFFy1/Android-PDF-Viewer/actions/workflows/ci.yml/badge.svg)](https://github.com/TFFy1/Android-PDF-Viewer/actions/workflows/ci.yml)

A fast, private PDF reader and toolkit for Android. **No ads. No tracking. No internet permission.**

Most free PDF apps pay for themselves with ads, analytics or upsells. This one doesn't: it reads,
annotates and edits PDFs entirely on your device, and it can't send your documents anywhere because
it isn't allowed to connect to the internet in the first place.

<!-- Screenshots: add images under fastlane/metadata/android/en-US/images/phoneScreenshots/
     and reference them here, e.g.
<p>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="240" alt="Library">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="240" alt="Reading">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="240" alt="Annotations">
</p>
-->
_Screenshots coming soon._

## Features

- **Open** from the file picker, "Open with" or "Share" from any app, your recents or library
  folders. Password-protected PDFs are supported.
- **Read** with continuous vertical scrolling or horizontal paging, pinch and double-tap zoom with
  sharp high-resolution tiles, page scrubber, go to page, outline/table of contents, thumbnails,
  internal and external links (external links ask first), night and sepia modes, fullscreen,
  keep screen on, volume-key paging, and resume at the last page.
- **Find** text with highlighted results.
- **Select** text with a long press, then copy or share it.
- **Annotate** with highlight, underline, strikethrough, freehand ink and sticky notes, with eraser
  and undo. Originals are never modified: export an annotated copy with standard PDF annotations.
- **Bookmarks** per document.
- **Tools**: merge, split, extract pages, rotate/reorder/delete pages, images to PDF, remove a known
  password, compress. Tools always write a new file.
- **Share and print** through Android's share sheet and print framework, save a copy, view
  document properties.
- **Settings**: light/dark/system theme, Material You dynamic color, default scroll and reading modes.

## Privacy promise

- The app declares **no `INTERNET` permission**, so it cannot make network connections.
- **No ads, no analytics, no crash-reporting SDKs, no trackers.**
- **No broad storage permission**: the app only sees files and folders you pick.
- Recents, bookmarks, annotations and settings stay on your device.

Details, including how Android backup is handled: [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

## Tech stack

| Area | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Rendering, text, search, links, outline | [Pdfium](https://pdfium.googlesource.com/pdfium/) via [`io.legere:pdfiumandroid`](https://github.com/johngray1965/PdfiumAndroidKt) |
| Writing PDFs (annotations, tools) | [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) |
| Persistence | Room (library, bookmarks, annotations), DataStore (settings) |
| Navigation | Navigation Compose with type-safe `@Serializable` routes |
| Dependency injection | Manual `AppContainer` (no framework) |
| Build | Android Gradle Plugin 9 (built-in Kotlin), Gradle 9, R8 full mode |

minSdk 26 (Android 8.0), targetSdk 36, compileSdk 37.

## Building

Requirements:

- JDK 17 or newer (JDK 21 recommended)
- Android SDK with platform 37 (Gradle installs missing SDK packages automatically once the licenses are accepted) — the easiest way is [Android Studio](https://developer.android.com/studio);
  on the command line, point `ANDROID_HOME` (or `sdk.dir` in `local.properties`) to your SDK

```bash
git clone https://github.com/TFFy1/Android-PDF-Viewer.git
cd Android-PDF-Viewer
./gradlew assembleDebug                 # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest lintDebug   # what CI runs, too
```

Install on a connected device with `./gradlew installDebug`. Debug builds use the application ID
`io.github.tffy1.pdfviewer.debug`, so they can be installed next to the store version.

Release builds are signed only in CI with repository secrets; nothing secret lives in this
repository. To sign locally, create an untracked `keystore.properties` in the project root
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). See
[docs/PUBLISHING.md](docs/PUBLISHING.md) for the full release process.

## Project structure

```
app/src/main/java/io/github/tffy1/pdfviewer/
├── PdfViewerApp, AppContainer, MainActivity   app entry and manual DI
├── navigation/                                routes, NavHost, bottom bar
├── core/model/                                page-space geometry
├── pdf/                                       engine contract + Pdfium implementation
├── data/                                      Room, DataStore, repositories
├── annotations/, library/                     annotation model/export, library logic
├── io/, integration/                          file access, intents, share, print
├── tools/                                     merge/split/rotate/... (PdfBox)
└── ui/                                        library, viewer, tools, settings, about, theme
.github/        CI and release workflows, issue templates, Dependabot
fastlane/       Google Play store listing texts (also usable by F-Droid)
docs/           architecture and publishing guides
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the design, the coordinate system and the
rules for contributors.

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) first. Report security
problems privately as described in [SECURITY.md](SECURITY.md).

## License

License: to be decided by the owner — until a LICENSE file is added, all rights reserved.

Third-party libraries used by the app are under their own open-source licenses (Apache-2.0, BSD, MIT); they are
listed in the app under Settings > About > Open-source licenses.
