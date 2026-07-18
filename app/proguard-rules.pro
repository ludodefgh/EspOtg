# Keep line numbers so stack traces from minified release builds are mappable -
# without this, on-device failure reports are undecipherable (learned from the
# "ClassCastException: a != java.lang.Long" incident, see CLAUDE.md).
-keepattributes SourceFile,LineNumberTable

# kotlinx.serialization: keep the generated serializers and serialized model
# shape for our domain classes. The library ships consumer rules, but they don't
# always survive aggressive optimization of small value-like model classes.
-keepclassmembers class com.espotg.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.espotg.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.espotg.core.**$$serializer { *; }

# flasher-native's JNI callback methods are covered by
# flasher-native/consumer-rules.pro (applied automatically).
