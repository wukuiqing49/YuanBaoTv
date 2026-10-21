# 按实际反射、序列化、路由或 WebView JSBridge 使用情况补充最小 keep 规则。

# SMBJ & MBassador
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**

# Media3、Room、Coil 与应用组件均使用各依赖 consumer rules 或直接引用，
# 不做整包 keep，避免关闭 R8 的压缩、优化与混淆能力。
