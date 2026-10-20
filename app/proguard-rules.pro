# Veil debug builds are shrunk to stay under 25 MB, never obfuscated.
-dontobfuscate
# all of Veil (activities, views from XML, reflection-free but kept whole for safety)
-keep class io.github.veilfilter.** { *; }
# ML runtimes: JNI entry points and classes looked up from native code
-keep class ai.onnxruntime.** { *; }
-keep class org.tensorflow.** { *; }
-keep class com.google.ai.edge.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
# litert2: Play "AI pack" model delivery is excluded on purpose (build.gradle.kts); its
# optional entry point (AiPackModelProvider) is never used by Veil
-dontwarn com.google.android.gms.tasks.**
-dontwarn com.google.android.play.core.aipacks.**
