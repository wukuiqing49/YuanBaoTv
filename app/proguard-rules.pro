# 按实际反射、序列化、路由或 WebView JSBridge 使用情况补充最小 keep 规则。

# SMBJ & MBassador
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-keep class com.hierynomus.smbj.** { *; }
-keep class com.hierynomus.mssmb2.** { *; }
-keep class com.hierynomus.protocol.** { *; }

# Media3 & ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Room Database
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Coil
-dontwarn coil.**
-keep class coil.** { *; }

# WKQ Core Base
-keep class com.wkq.base.** { *; }
