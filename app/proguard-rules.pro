# R8 rules for the release build (owner: release/CI).
#
# AGP 9 runs R8 in full mode. Most dependencies ship their own consumer rules
# (AndroidX, Room, DataStore, kotlinx.serialization, pdfiumandroid, pdfbox-android),
# so this file only adds what those rules miss. Every rule below was checked against
# the actual 2.0.3 / 2.0.27.0 artifacts; see the notes before relaxing anything.
#
# If a release-only crash shows up, the mapping file for that build is uploaded by
# .github/workflows/release.yml (artifact "mapping"); retrace the stack trace with it.

# Keep line numbers for readable crash reports, but hide the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------------
# Pdfium (io.legere:pdfiumandroid 2.0.3 + -api + -core)
# ---------------------------------------------------------------------------------
# pdfiumandroid's own consumer rules already keep io.legere.pdfiumandroid.** entirely.
# The rules below pin down what libpdfiumandroid.so needs from Java, so a future
# upgrade with narrower consumer rules can't silently break the JNI bridge.
# Classes the native code looks up by name (FindClass) or registers natives on:
-keep class io.legere.pdfiumandroid.core.jni.** { *; }
# Native code calls PdfiumNativeSourceBridge.read(long, long) for custom sources.
-keep class io.legere.pdfiumandroid.core.util.PdfiumNativeSourceBridge { *; }
# Native code calls PdfWriteCallback.WriteBlock(byte[]) when saving.
-keep interface io.legere.pdfiumandroid.api.PdfWriteCallback { *; }
-keep class * implements io.legere.pdfiumandroid.api.PdfWriteCallback {
    public int WriteBlock(byte[]);
}
# Native code throws PdfPasswordException by name for encrypted documents.
-keep class io.legere.pdfiumandroid.api.PdfPasswordException { <init>(...); }

# ---------------------------------------------------------------------------------
# PdfBox-Android (com.tom-roush:pdfbox-android 2.0.27.0)
# ---------------------------------------------------------------------------------
# Its consumer rules keep SecurityHandler subclass constructors and
# pdmodel.documentinterchange.** (both used reflectively).
#
# Fonts, glyph lists, AFM metrics and CMaps ship as *assets* under
# assets/com/tom_roush/... and are read via PDFBoxResourceLoader (initialised in
# PdfViewerApp). Resource shrinking (isShrinkResources) never touches assets, so no
# keep rules are needed for them.
#
# PDNumberTreeNode instantiates its value type reflectively via
# getDeclaredConstructor(COSDictionary) — for page labels that is PDPageLabelRange.
-keepclassmembers class com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange {
    public <init>(com.tom_roush.pdfbox.cos.COSDictionary);
}

# Optional JPEG 2000 decoder (com.gemalto.jp2:jp2-android). PdfBox probes for it with
# Class.forName and degrades gracefully; we don't ship it.
-dontwarn com.gemalto.jp2.**

# BouncyCastle (bcprov/bcpkix/bcutil 1.72, transitive via PdfBox) has LDAP cert-store
# classes that reference JNDI, which does not exist on Android.
-dontwarn javax.naming.**
# BouncyCastle is only used for certificate (public-key) encrypted PDFs, which we can't
# open without the recipient's private key anyway. Its JCA algorithm classes are loaded
# by name and are NOT kept, so that path fails with a normal IOException. Keep
# org.bouncycastle.jcajce.provider.** and org.bouncycastle.jce.provider.** if the app
# ever needs BouncyCastle-backed crypto.

# ---------------------------------------------------------------------------------
# Guava (runtime dependency of pdfiumandroid)
# ---------------------------------------------------------------------------------
# Guava's AbstractFuture/UnsignedBytes fall back gracefully when sun.misc.Unsafe is
# unavailable; the class is not part of android.jar.
-dontwarn sun.misc.Unsafe

# ---------------------------------------------------------------------------------
# kotlinx.serialization (type-safe navigation routes + annotation JSON payloads)
# ---------------------------------------------------------------------------------
# kotlinx-serialization-core 1.9 bundles full-mode R8 rules (Companion, serializer(),
# INSTANCE of @Serializable objects). These are kept as a safety net in case a route
# or payload class is ever looked up reflectively via KClass.serializer().
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
