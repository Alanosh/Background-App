# Keep MediaPipe classes
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.tasks.** { *; }

# Keep TFLite
-keep class org.tensorflow.lite.** { *; }

# Keep our data classes (used by StateFlow / reflection)
-keep class com.personal.vbr.core.** { *; }

# Suppress warnings for optional dependencies
-dontwarn org.tensorflow.**
-dontwarn com.google.mediapipe.**
