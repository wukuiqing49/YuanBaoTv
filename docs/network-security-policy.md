# NAS 网络安全边界

圆宝 TV 支持用户配置的 HTTP/HTTPS WebDAV。主机和 IP 在运行时添加，Android 静态 XML 无法预先列举，因此保留 app 的 cleartext 平台许可，作为明确业务例外；不把此例外复制到其他项目。

- WebDavEndpoint 校验协议、规范化主机、有效端口与区分大小写的目录边界。扫描、下载、封面和播放共用该策略。
- WebDavClientManager 不跟随重定向，不关闭 TLS 或主机名校验，不修改进程全局 HTTPS 设置。
- 播放和封面层拒绝未配置来源的 HTTP；普通 HTTPS 封面可匿名读取。
- Basic Auth 在 HTTP 上传输时不加密；界面明确提示只在可信网络使用 HTTP。需要保密时配置 HTTPS。
- 保留系统 CA 与用户主动安装的 CA 信任，支持家庭 NAS 私有证书；不自动信任自签证书。未知证书必须安装可信 CA 或更换有效证书。
- 例外仅适用于此 app 的指定 XML。静态门禁仍拒绝未登记的全局明文配置及 TLS 绕过代码；登记和测试不替代发布前网络实测。

验证：WebDavEndpointTest 覆盖 HTTP/HTTPS、默认端口、IPv6、根路径、跨源和目录逃逸。WebDavPlaybackTest 使用本机 HTTP 服务器覆盖 Range、鉴权及拒绝重定向。设备验证与限制记录在本次优化报告。
