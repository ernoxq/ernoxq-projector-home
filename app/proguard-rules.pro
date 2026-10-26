# Started by name through app_process for instant key presses: keep it as is.
-keep class md.grin.projector.KeyServer { *; }
# ADB library: keep everything it needs, ignore optional platform classes it references.
-keep class dadb.** { *; }
-dontwarn **
