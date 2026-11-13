# Keep the JNI entry points if release shrinking is enabled later.
-keep class com.audioassistant.engine.NativeBridge { *; }
-keep class com.audioassistant.engine.NativeProgress { public void onProgress(double); }
-keepclasseswithmembernames class * { native <methods>; }
