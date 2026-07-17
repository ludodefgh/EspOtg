# The onNative*/native* methods on EspLoaderNative are only ever called from C
# (android_port.c/jni_bridge.c) via JNI method-ID lookup, never from Kotlin/Java
# bytecode - R8 would otherwise consider them unused and strip or rename them,
# breaking the native lookups silently in release builds only.
-keep class com.espotg.flasher.EspLoaderNative {
    <init>(...);
    private *** onNative*(...);
    private native <methods>;
}
