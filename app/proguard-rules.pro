# Rules are maintained in docs/ARCHITECTURE.md ownership: release/CI agent.

# Pdfium: JNI bridge classes are looked up from native code.
-keep class io.legere.pdfiumandroid.** { *; }
-keep class com.shockwave.** { *; }

# PdfBox-Android
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.**

# kotlinx.serialization (navigation routes + annotation payloads)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
