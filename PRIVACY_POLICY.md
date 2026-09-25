# Privacy Policy — PDF Viewer

**Effective date: 25 September 2026**

This policy covers the Android app **PDF Viewer** (package `io.github.tffy1.pdfviewer`),
published on Google Play and on GitHub at <https://github.com/TFFy1/Android-PDF-Viewer>.

## The short version

- The app **collects no data**. We (the developers) never receive anything from your device.
- The app **has no internet permission**, so it cannot connect to the internet at all.
- There are **no ads, no analytics, no crash reporting and no third-party trackers**.
- Everything the app remembers stays **on your device** (and in your own Android backup, if you
  have turned backups on — see below).

## What the app stores on your device

To work, the app keeps a small amount of information in its private storage on your phone or
tablet. Other apps cannot read it.

| What | Why |
|---|---|
| Recent documents: file name, size, page count, the last page you read, whether it's a favorite, and a reference to the file (a content URI) | So you can reopen files and continue where you left off |
| Library folders you added, and the list of PDFs found in them | To show your library |
| Bookmarks (page numbers and titles) | Per-document bookmarks |
| Annotations you create (highlights, ink, sticky notes and their text) | So you can edit them before exporting an annotated copy |
| Settings (theme, reading mode, etc.) | To remember your preferences |
| Small cover thumbnails of recent documents, and copies you create with the PDF tools or when exporting annotations | To display covers and to save or share the files you created |

The app does **not** copy your PDFs into its storage just by opening them. It reads them from where
they are, through the file you picked or the app you opened them from.

You can delete this data at any time by removing items from your recents/library in the app, or by
clearing the app's storage (Android Settings > Apps > PDF Viewer > Storage > Clear storage), or by
uninstalling the app.

## Backups (Android Auto Backup)

Android can back up app data to **your own Google account** and restore it on a new device. This is
controlled by you in Android Settings (usually Settings > System > Backup), is handled by Google
under Google's privacy policy, and is not accessible to us. Google encrypts backups; on Android 9
and later with a screen lock set, they are end-to-end encrypted with your PIN, pattern or password.

What this app allows to be included:

- **Cloud backup (to your Google account) and direct device-to-device transfer:** the app's database
  (recent documents list, library folders, bookmarks and annotations) and its settings. Thumbnails,
  exported copies and tool outputs are **excluded**, and so is anything in shared/external storage.
- Temporary cache files are never backed up.

Note that restored file references may not open on a new device, because access to a file is
granted per device; you may need to open those files again.

If you don't want any of this, turn off backup for your device in Android Settings.

## Permissions

The app requests **no dangerous (runtime) permissions** and no broad storage permission:

- **No internet permission.** The app cannot send or receive data over the network.
- **No "all files access".** The app can only open files and folders that you explicitly choose in
  the Android file picker, or that another app hands to it ("Open with" or "Share").
- The app keeps access to the files and folders you chose so that your recents and library keep
  working. You can revoke this by removing them from the app or clearing its storage.

## When data leaves the app — only when you decide

Data only leaves the app when you do it yourself, for example when you:

- **Share** or **save a copy** of a document, or share selected text — it goes to the app or location you pick;
- **Copy** text — it goes to the Android clipboard;
- **Print** — the document is passed to the Android print service you choose;
- **Tap a web link** inside a PDF — the app asks for confirmation and then hands the link to your browser.
  The app itself makes no network connection.

## Children

The app does not collect personal information from anyone, including children.

## Open source

The app's source code is public, so anyone can verify these statements:
<https://github.com/TFFy1/Android-PDF-Viewer>

## Changes to this policy

If this policy changes, the new version will be published at the same address with a new effective
date. The history of every change is visible in the repository.

## Contact

For questions about this policy, open an issue at
<https://github.com/TFFy1/Android-PDF-Viewer/issues>.
To report a security problem privately, see [SECURITY.md](SECURITY.md).
