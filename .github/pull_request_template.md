## What does this change?

<!-- A short summary. Link the issue it fixes, e.g. "Fixes #123". -->

## How was it tested?

<!-- Unit tests added/updated, devices/emulators and Android versions you tried, screenshots for UI changes. -->

## Checklist

- [ ] `./gradlew assembleDebug testDebugUnitTest lintDebug` passes locally
- [ ] New user-visible strings are in the feature's `res/values/strings_<feature>.xml`
- [ ] Pure logic has JUnit tests under `app/src/test`
- [ ] No heavy work (file I/O, rendering, PdfBox) on the main thread
- [ ] UI works in dark theme, with large fonts, TalkBack (content descriptions) and RTL layouts
- [ ] No new permissions, network access, analytics or ads
- [ ] No secrets, keystores, `local.properties` or personal data are committed
- [ ] If a dependency changed: `app/proguard-rules.pro` still fits (CI "Release build (R8)" job passes)
