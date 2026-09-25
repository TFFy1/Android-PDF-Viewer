# Contributing

Thanks for helping improve PDF Viewer! Bug reports, ideas and pull requests are welcome.

## Ground rules

- **Privacy first.** The app has no internet permission, no ads, no analytics and no trackers.
  Pull requests that add network access, tracking SDKs, ads or new broad permissions won't be merged.
- **Non-destructive.** Never modify a user's original PDF in place; tools and exports write new files.
- **No secrets.** This repository is public. Never commit keystores, passwords, API keys,
  `local.properties`, `keystore.properties`, service-account JSON or personal data (including
  PDFs or screenshots containing personal information). `.gitignore` covers the usual files, but
  please double-check your diff.
- Security issues go through private advisories, see [SECURITY.md](SECURITY.md).

## Getting started

Requirements: JDK 17 or newer (JDK 21 recommended), Android SDK with platform 37 (Android Studio
installs it), and a device or emulator running Android 8.0 (API 26) or later.

```bash
./gradlew assembleDebug          # build the debug APK
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew lintDebug              # Android lint (warnings configured as errors fail CI)
```

The debug build installs next to a release build (application ID suffix `.debug`).

Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before making larger changes: it explains the
package layout, the page-space coordinate system and which parts are shared contracts.

## Code style

- Kotlin official code style (`kotlin.code.style=official`), 4-space indentation, no wildcard imports.
- Jetpack Compose + Material 3. Every icon-only button needs a `contentDescription`; keep touch
  targets at least 48dp; make sure layouts work in dark theme, with large fonts and in RTL languages.
- No blocking work on the main thread: file I/O, rendering and PdfBox operations run on background
  dispatchers. All native Pdfium calls go through the engine, which serializes them.
- Handle bad input gracefully: corrupt or password-protected files and revoked file permissions must
  show an error, not crash.
- Guard APIs newer than API 26 with `Build.VERSION.SDK_INT` checks (lint `NewApi` fails the build).
- Keep comments for the "why"; avoid dead code.

## Strings and translations

- Each feature keeps its user-visible strings in its own file, `app/src/main/res/values/strings_<feature>.xml`,
  with names prefixed by the feature (e.g. `viewer_`, `search_`, `library_`, `tools_`).
- Shared strings live in `res/values/strings.xml`.
- Never hard-code user-visible text in Kotlin code.

## Tests

- Put pure logic (parsing, geometry, page-range math, state reducers...) in plain Kotlin classes and
  cover it with JUnit 4 tests under `app/src/test/java/io/github/tffy1/pdfviewer/<area>/`.
- Unit tests must not depend on Android framework classes.
- CI runs `assembleDebug testDebugUnitTest lintDebug` and a minified release build on every push
  and pull request; please make sure they pass.

## Dependencies

- Prefer AndroidX and Kotlin libraries that are already in `gradle/libs.versions.toml`.
- New dependencies must be open source with a license compatible with distribution on Google Play,
  must not add permissions or network access, and must not include tracking.
- If a dependency uses reflection or JNI, check that `app/proguard-rules.pro` still covers it
  (the CI "Release build (R8)" job catches missing classes, not runtime reflection).

## Pull requests

1. Fork the repository and create a branch from the default branch.
2. Keep pull requests focused: one feature or fix per PR.
3. Fill in the pull request template, including how you tested the change.
4. Add screenshots for UI changes (using sample documents with no personal data).

## License

The project has not chosen a license yet (see the License section of the [README](README.md)).
Until a `LICENSE` file is added, please open an issue to discuss larger contributions first.
