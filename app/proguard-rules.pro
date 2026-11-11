# 发布签名（保持默认规则即可，暂不混淆）
# 若后续开启 minify，需补：kotlinx.serialization 的 @Serializable 类、OkHttp
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class cn.yunqiao.icloud.**$$serializer { *; }
-keepclassmembers class cn.yunqiao.icloud.** {
    *** Companion;
}
-keepclasseswithmembers class cn.yunqiao.icloud.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
