# Security Policy

## Supported versions

Only the latest release (on Google Play and GitHub Releases) receives security fixes.

## Reporting a vulnerability

**Please do not report security problems in public issues, discussions or pull requests.**

Report them privately through GitHub's security advisories:

1. Go to <https://github.com/TFFy1/Android-PDF-Viewer/security/advisories/new>
   (or the repository's **Security** tab > **Report a vulnerability**).
2. Describe the problem, the affected app version and Android version, and the steps to reproduce.
3. If a crafted PDF is needed to reproduce it, attach it to the advisory (not to a public issue).

What to expect:

- An acknowledgement within 7 days.
- An assessment and, if confirmed, a plan for a fix. We'll keep you updated in the advisory.
- Credit in the release notes if you want it.

Please give us a reasonable time to ship a fix before disclosing the issue publicly.

## Scope

In scope, for example:

- Crashes or memory corruption triggered by a crafted PDF (the app renders PDFs with Pdfium and
  writes them with PdfBox-Android).
- Ways for another app to read this app's private data, or to make it open or overwrite files
  without the user's consent (exported components, intents, `FileProvider`).
- Anything that would make the app send data off the device.

Out of scope: vulnerabilities in Android itself or in a PDF you created yourself only to crash the
app on your own device without any further impact (please still report those as normal bugs).

## For contributors

This repository is public. Never commit keystores, passwords, API keys, service-account JSON,
`local.properties`, `keystore.properties` or personal data. Release signing uses GitHub Actions
secrets only (see [docs/PUBLISHING.md](docs/PUBLISHING.md)). If you accidentally committed a
secret, treat it as compromised: rotate it first, then remove it from history.
