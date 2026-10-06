# whisper_jni.cpp looks the progress callback up by name (GetMethodID "onProgress"), so neither the
# interface nor its method may be renamed or removed. The native methods themselves are covered by the
# default Android rules (classes with native methods keep their names).
-keep interface com.dpx.captions.whisper.WhisperLib$ProgressListener { *; }
-keep class com.dpx.captions.whisper.WhisperLib { *; }
