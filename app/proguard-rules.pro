# ProGuard rules
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Native byedpi bridge: JNI looks up symbols by the exact class+method name
# (Java_com_tgwsproxy_core_ByeDpiProxy_jni*), so R8 must not rename/strip it.
-keep class com.tgwsproxy.core.ByeDpiProxy { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
