# JNI resolves these Java names/members from native libraries. Keep the Java/JNI
# boundaries; the native .so files and model assets are not changed by this pass.
-keep class ai.onnxruntime.** { *; }
-keep class org.opencv.** { *; }
-keep,allowoptimization class com.mangareader.translate.LlamaBridge {
    native <methods>;
}

# IPADIC's Builder passes getClass() to SimpleResourceResolver, which reads the
# eight dictionary resources relative to com/atilika/kuromoji/ipadic/. Retain this
# resource anchor's identity while allowing unrelated Kuromoji code to shrink.
-keepnames class com.atilika.kuromoji.ipadic.Tokenizer$Builder

# Worker class names also exist in persisted WorkManager rows across upgrades.
-keepnames class com.mangareader.ui.CoreModelsWorker
-keepnames class com.mangareader.ui.PageImportWorker
-keepnames class com.mangareader.ui.TranslationWorker

# Ktor's SLF4J 1.x facade intentionally falls back to its no-op logger when no
# optional binding is installed. This is the only missing-class warning suppressed.
-dontwarn org.slf4j.impl.StaticLoggerBinder
