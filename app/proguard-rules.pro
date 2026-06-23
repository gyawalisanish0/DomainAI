# --- llama.cpp JNI bridge ---
# The native lib exposes name-based symbols (Java_sg_act_domain_llama_*) and calls
# back into IntVar via GetMethodID("getValue"/"inc"). R8 must not rename any of it,
# or on-device model loading breaks in release (minify is off in debug, so it only
# surfaces in the signed build).
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class sg.act.domain.llama.LLamaAndroid { *; }
-keep class sg.act.domain.llama.LLamaAndroid$IntVar { *; }

# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class **$$serializer {
    *** serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    *** INSTANCE;
}
