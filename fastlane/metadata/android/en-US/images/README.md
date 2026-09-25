# Store listing graphics

Google Play (and F-Droid, which reads the same `fastlane/metadata/android` layout) picks up
images from these folders. Binary images are not committed yet; add them before publishing.
Never include personal or confidential documents in screenshots: use sample PDFs that you are
allowed to publish (e.g. your own or public-domain documents).

| Path | What | Size / format |
|---|---|---|
| `icon.png` | Hi-res app icon | 512 x 512 px, 32-bit PNG with alpha, max 1 MB |
| `featureGraphic.png` | Feature graphic (shown at the top of the listing) | 1024 x 500 px, JPEG or 24-bit PNG (no alpha) |
| `phoneScreenshots/1.png` ... | Phone screenshots | 2 to 8 images, JPEG or 24-bit PNG, 320–3840 px per side, max side no more than 2x the min side. 1080 x 1920 or 1080 x 2400 portrait works well |
| `sevenInchScreenshots/` | 7" tablet screenshots (optional, recommended) | Same rules as phone screenshots |
| `tenInchScreenshots/` | 10" tablet screenshots (optional, recommended) | Same rules as phone screenshots, e.g. 1600 x 2560 or 2560 x 1600 |

Check the current requirements in Play Console > Grow users > Store presence > Main store listing,
as Google adjusts them from time to time.

## Suggested phone screenshots

1. Library: recent documents with covers and favorites
2. Reading a document (light theme), page indicator visible
3. Night mode reading
4. Search with highlighted results
5. Annotations: highlight, ink and a sticky note
6. PDF tools screen (merge, split, rotate, compress...)
7. Settings screen, showing the "no internet permission" privacy statement in About

Add a short caption to each screenshot only if it stays readable at thumbnail size, and keep the
feature graphic free of claims like "No. 1" or "Best" (Play metadata policy).
