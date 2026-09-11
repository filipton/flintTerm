# JNA + uniffi
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class dev.flint.term.core.** { *; }
-dontwarn java.awt.**

# The frame path's JNI entry points are looked up by class and method name.
-keep class dev.flint.term.terminal.FrameBridge { *; }
