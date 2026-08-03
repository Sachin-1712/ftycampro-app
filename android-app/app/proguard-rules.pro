# Keep line numbers so release crash reports stay readable. There is no crash
# reporting service in this app — logs stay on the device — but a stack trace
# read over adb is still worth having.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Media3 uses reflection to select decoders and extractors.
-dontwarn androidx.media3.**
-keep class androidx.media3.exoplayer.** { *; }

# Transport implementations are looked up by name in AppContainer when a new
# protocol is added; keep their constructors intact.
-keep class dev.ftycam.transport.** { *; }
