# ---- Media3 / ExoPlayer -------------------------------------------------
# Media3 ships its own consumer rules; these keeps protect reflection based
# renderer/extractor lookups used while playing exotic containers from USB.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ---- Coil ---------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**

# ---- java.util.zip / file operations ------------------------------------
-keepclassmembers class com.usbmediaexplorer.data.ops.** { *; }

# ---- Kotlin coroutines --------------------------------------------------
-dontwarn kotlinx.coroutines.**

# Keep line numbers for readable crash reports on external storage I/O.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
