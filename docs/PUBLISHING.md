# Publishing PDF Viewer on Google Play

A step-by-step guide for the repository owner. It covers the one-time setup (signing key, GitHub
secrets, Play Console) and the routine for every release.

> Google changes Play Console screens and policies regularly. Where this guide quotes a policy
> (tester counts, target SDK deadlines, graphic sizes), double-check the current Play Console Help
> before relying on it.

---

## 1. Create the upload keystore (once)

The upload key signs the bundles you send to Google. With Play App Signing (step 6), Google keeps
the real app signing key, so a lost upload key can be reset — but keep it safe anyway.

On your own computer (not in the repository folder):

```bash
keytool -genkeypair -v \
  -keystore pdfviewer-upload.jks \
  -storetype PKCS12 \
  -alias upload \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname "CN=PDF Viewer upload key"
```

`keytool` ships with every JDK. It asks for a keystore password. With the PKCS12 store type the key
password is the same as the keystore password.

- Store `pdfviewer-upload.jks` and its password in a password manager, and keep an offline backup.
- **Never commit it.** `.gitignore` already ignores `*.jks`, `*.keystore` and `keystore.properties`,
  but check `git status` anyway.

## 2. Add the GitHub secrets (once)

The release workflow (`.github/workflows/release.yml`) reads four repository secrets. Go to
**GitHub > repository > Settings > Secrets and variables > Actions > New repository secret**:

| Secret | Value |
|---|---|
| `PDFVIEWER_KEYSTORE_BASE64` | The keystore file, base64-encoded (see below) |
| `PDFVIEWER_KEYSTORE_PASSWORD` | Keystore password |
| `PDFVIEWER_KEY_ALIAS` | `upload` (the `-alias` you used) |
| `PDFVIEWER_KEY_PASSWORD` | Key password (same as the keystore password for PKCS12) |

The fifth value the build needs, `PDFVIEWER_KEYSTORE_FILE`, is not a secret: the workflow decodes
the keystore into the runner's temp folder, points the variable at it, and deletes the file at the
end of the job.

Base64-encode the keystore as a single line:

```bash
# Linux
base64 -w 0 pdfviewer-upload.jks > keystore.b64
# macOS
base64 -i pdfviewer-upload.jks -o keystore.b64
# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("pdfviewer-upload.jks")) | Out-File -Encoding ascii keystore.b64
```

Paste the content of `keystore.b64` as the secret value, then delete `keystore.b64`. With the
[GitHub CLI](https://cli.github.com/) you can skip the intermediate file:

```bash
base64 -w 0 pdfviewer-upload.jks | gh secret set PDFVIEWER_KEYSTORE_BASE64
gh secret set PDFVIEWER_KEYSTORE_PASSWORD   # prompts for the value
gh secret set PDFVIEWER_KEY_ALIAS
gh secret set PDFVIEWER_KEY_PASSWORD
```

Recommended hardening, since pushing a tag starts a signed build:

- **Settings > Rules > Rulesets > New tag ruleset**: target `v*`, restrict creation, update and
  deletion to repository admins.
- Secrets are never exposed to pull requests from forks, and the release workflow runs only on
  `v*` tags and manual runs.

### Signing locally (optional)

To build a signed release on your machine, create `keystore.properties` in the project root
(it's git-ignored):

```properties
storeFile=/absolute/path/to/pdfviewer-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Then run `./gradlew bundleRelease`.

## 3. Versioning

Versions come from the git tag:

| Tag | `versionName` | `versionCode` |
|---|---|---|
| `v1.0.0` | `1.0.0` | `10000` |
| `v1.0.1` | `1.0.1` | `10001` |
| `v1.2.3` | `1.2.3` | `10203` |
| `v2.0.0` | `2.0.0` | `20000` |

`versionCode = MAJOR * 10000 + MINOR * 100 + PATCH`, so **MINOR and PATCH must stay between 0 and 99**.
The workflow rejects any other tag format (e.g. `v1.2`, `v1.2.3-beta`). Every upload to Play needs a
higher `versionCode` than all previous uploads, even rejected or internal-only ones: if a build has
a problem, fix it and tag the next patch version rather than re-using a tag.

The workflow passes the values to Gradle as `PDFVIEWER_VERSION_NAME` and `PDFVIEWER_VERSION_CODE`
and fails if the built APK doesn't carry them. Local builds without these variables use the defaults
in `app/build.gradle.kts`.

## 4. Tag a release

1. Make sure CI is green on the commit you want to release (including the "Release build (R8)" job).
2. Write the release notes (max 500 characters) in
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, e.g. `changelogs/10000.txt` for
   `v1.0.0`. The same text is used for the GitHub Release and the Play Console "Release notes".
3. Commit, then tag and push:

   ```bash
   git tag -a v1.0.0 -m "PDF Viewer 1.0.0"
   git push origin v1.0.0
   ```

4. Watch **Actions > Release**. The workflow:
   - checks that all secrets exist and that the tag is a valid version,
   - builds `bundleRelease` and `assembleRelease` with R8,
   - verifies both outputs are signed and correctly versioned,
   - uploads artifacts `release-aab`, `release-apk` (with `SHA256SUMS.txt`) and `mapping`,
   - creates a **draft** GitHub Release with the APK attached.

You can also start the workflow manually (**Actions > Release > Run workflow**) with an optional
version, to test signing without publishing anything; manual runs don't create a GitHub Release.

## 5. Download the build

From the workflow run page, download `release-aab` (for Play) and `mapping` (keep it with the
release; it's needed to read obfuscated crash stack traces). Or with the GitHub CLI:

```bash
gh run list --workflow release.yml
gh run download <run-id> -n release-aab -n mapping
```

Artifacts expire (the mapping after 90 days), so also store `mapping-<version>.txt` somewhere
permanent, e.g. attach it to the GitHub Release or keep it next to your keystore backup.

Before uploading, install the APK from `release-apk` on a real device and smoke-test the release
build: open a normal and a password-protected PDF, search, select text, add and export annotations,
merge and split, share and print. R8 only affects release builds, so this is the place where a
missing keep rule would show up.

Review the draft GitHub Release (notes, attached APK) and publish it when the Play release is out.

## 6. Create the app in Play Console (once)

1. Create a developer account at <https://play.google.com/console> (one-time fee, identity
   verification; organization accounts also need a D-U-N-S number).
2. **Create app**: app name `PDF Viewer: Read & Annotate` (or your choice, max 30 characters),
   default language English (United States), **App**, **Free**, accept the declarations.
   A free app can't be changed to paid later.
3. **Play App Signing**: when you upload the first AAB, keep the default "Google-generated key".
   Your keystore becomes the *upload key*. If you ever lose it, request an upload key reset in
   **Test and release > Setup > App signing**.

   Note: APKs on GitHub Releases are signed with your *upload* key, while Play installs are signed
   with Google's *app signing* key. Android won't update one with the other, so users must uninstall
   before switching between GitHub and Play installs. Mention this in the GitHub Release notes, or
   choose "Use a different key" (upload your own key with the PEPK tool) during setup if you want
   both channels to share one signature.

## 7. Store listing (Grow users > Store presence > Main store listing)

Copy the texts from `fastlane/metadata/android/en-US/`:

| Field | File | Limit |
|---|---|---|
| App name | `title.txt` | 30 |
| Short description | `short_description.txt` | 80 |
| Full description | `full_description.txt` | 4000 |

- **Graphics**: app icon 512 x 512 PNG, feature graphic 1024 x 500, 2–8 phone screenshots, and
  optional tablet screenshots. Requirements and suggested shots are in
  `fastlane/metadata/android/en-US/images/README.md`.
- **Category** (Store settings): App > **Productivity**. Tags: e.g. "PDF reader", "Document editor".
- **Contact details**: a contact email is required and shown publicly — use a dedicated address,
  not a personal one. Website: `https://github.com/TFFy1/Android-PDF-Viewer`.

The folder layout follows the fastlane `supply` convention, so you can automate uploads later
(`fastlane supply`, requires a Play service account key — store it as a GitHub secret, never in the
repository).

## 8. App content (Policy and programs > App content)

- **Privacy policy URL**:
  `https://github.com/TFFy1/Android-PDF-Viewer/blob/<default-branch>/PRIVACY_POLICY.md`
  (the page must be public; a GitHub Pages URL works too).
- **Ads**: "No, my app does not contain ads".
- **App access**: "All functionality is available without special access".
- **Content rating**: fill in the questionnaire with category "All other app types" (utility /
  productivity). Answer **No** to violence, sexuality, language, controlled substances, gambling,
  user-to-user interaction or chat, sharing location, and digital purchases. Expect ratings like
  Everyone / PEGI 3 / USK 0.
- **Target audience and content**: choose age groups **13 and over** (e.g. 13–15, 16–17, 18+). The
  app is fine for everyone, but including under-13 groups puts it under the Families policy with
  extra requirements. Answer that the app is not designed to appeal to children.
- **Data safety**:
  - "Does your app collect or share any of the required user data types?" → **No**.
  - This makes the listing show "No data collected" and "No data shared with third parties".
  - Rationale: the app has no network access; everything is processed and stored on the device.
    Google's definition of "collection" is data transmitted off the device to you or a third party.
    Android's system backup is run by Google for the user under their account settings. Re-check the
    current "Data safety" help page if the app ever changes.
- **Advertising ID**: "No" — the app doesn't use it and doesn't declare `AD_ID`.
- **Government apps**, **Financial features**, **Health**, **News**: not applicable / No.

Verify the permissions of each release before answering the forms again:

```bash
$ANDROID_HOME/build-tools/<version>/aapt2 dump permissions pdfviewer-1.0.0.apk
```

Expect no `android.permission.INTERNET` and no storage permissions; AndroidX adds only the
signature-level `io.github.tffy1.pdfviewer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Play Console's
**App bundle explorer** shows the same list.

## 9. Testing tracks

1. **Internal testing** (Test and release > Testing > Internal testing): create a release, upload
   the AAB, paste the changelog, roll out. Add up to 100 testers by email list and install through
   the opt-in link. Builds are available within minutes and are a good first check of the signed
   bundle, Play App Signing and the pre-launch report.
2. **Closed testing** — required for new *personal* developer accounts (created after
   13 November 2023): before you can apply for production access, a closed test must have had at
   least **12 testers opted in for at least 14 consecutive days** (this was 20 testers until late
   2024; verify the current numbers in Play Console Help "App testing requirements for new personal
   developer accounts"). Organization accounts are exempt.
   - Create a closed track, add testers (email list or Google Group), share the opt-in link.
   - Keep testers opted in for the full period; ship at least one update during the test and collect
     feedback — the production-access application asks about it.
3. **Apply for production** (Dashboard) once the requirement is met, and answer the questionnaire
   about your testing.

## 10. Production release

1. Test and release > Production > Create new release, add the AAB (or promote the tested release).
2. Paste the release notes from `changelogs/<versionCode>.txt`.
3. Use a **staged rollout** (e.g. 20%), watch Android vitals (crashes, ANRs) for a few days, then
   increase to 100%.
4. The first review of a new app can take several days; later updates are usually faster.
5. Publish the draft GitHub Release.

If Play Console warns "no deobfuscation file", upload `mapping-<version>.txt` under
**App bundle explorer > Downloads > Assets** for that version (the mapping is normally embedded in
the AAB).

## 11. Every later release

1. Bump nothing in code — the tag sets the version.
2. Add `changelogs/<new versionCode>.txt`.
3. Tag `vX.Y.Z`, push, download the AAB, smoke-test the APK, upload to internal testing, then
   promote to production.

## 12. Ongoing Play requirements

- **Target API level**: Google Play requires new apps and updates to target an API level within about
  one year of the latest major Android release (the deadline is usually 31 August each year). The app
  targets **API 36 (Android 16)**, which meets the level Play requires during 2026. Plan to raise
  `targetSdk` (`compileSdk` is already 37) to the next level before the 2027 deadline and re-test behavior
  changes. Check the current table in Play Console Help "Target API level requirements".
- **16 KB memory page size**: required for apps targeting Android 15+ with native code. The bundled
  Pdfium libraries (`arm64-v8a`, `x86_64`) are built with 16 KB-aligned segments, and AGP 9 packages
  them correctly. Play Console's App bundle explorer flags any regression.
- **Policy declarations** (Data safety, content rating, target audience) must be kept accurate;
  revisit them whenever a feature touches permissions or data.
- **Developer account**: keep contact information verified and log in periodically; inactive
  accounts can be closed.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Missing secret" error in the Release workflow | Add the named secret (step 2). |
| "Unsigned build" error | The keystore file wasn't available to Gradle; re-create `PDFVIEWER_KEYSTORE_BASE64` as a single line. |
| Gradle: "Keystore was tampered with, or password was incorrect" / alias not found | Check `PDFVIEWER_KEYSTORE_PASSWORD`, `PDFVIEWER_KEY_ALIAS`, `PDFVIEWER_KEY_PASSWORD`. |
| "Version mismatch" error | `app/build.gradle.kts` must read `PDFVIEWER_VERSION_CODE` / `PDFVIEWER_VERSION_NAME`. |
| "Bad version" error | Use tags of the form `vMAJOR.MINOR.PATCH`, MINOR/PATCH ≤ 99. |
| Play: "Version code already used" | Tag the next patch version. |
| Play: "APK signed with the wrong key" | You uploaded with a different keystore than the registered upload key; use the original or request an upload key reset. |
| Release-only crash (`ClassNotFoundException`, `NoSuchMethodException`) | Retrace with the mapping file, then add a keep rule in `app/proguard-rules.pro`. |
