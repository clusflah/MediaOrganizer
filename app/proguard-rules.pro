# OkHttp 自带 consumer rules；这里只压掉可选依赖的告警
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# smbj：内部事件总线靠注解找处理方法，不能被裁掉
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class * { @net.engio.mbassy.listener.Handler *; }
-keepattributes *Annotation*,Signature
-dontwarn org.slf4j.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn com.hierynomus.**
-dontwarn net.engio.**
