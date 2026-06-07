# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools proguard-android.txt file.

# Keep Camera2 classes
-keep class android.hardware.camera2.** { *; }
-keep class android.media.DngCreator { *; }
