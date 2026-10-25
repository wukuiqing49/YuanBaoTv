# 小米系统播放器对照测试

## 范围

按用户要求，测试电视自带小爱配合电视系统播放器，不部署 NAS 服务、不增加音箱或其他语音入口。遵循 fix-bug Workflow；本轮没有修改 App 运行代码或默认播放方式。

## 测试素材

本机 FFmpeg 合成五分钟 640×360、15fps H.264/AAC 测试视频，含动态色块与低音量测试音。没有使用用户媒体或 NAS 凭据。辅助生成工具仅安装在忽略的 build 输出目录，不是项目依赖。

电视测试文件 `/sdcard/Movies/yuanbao-system-player-test.mp4`，约 26 MB。测试材料保存在忽略目录 `app/build/outputs/xiaoai-audit/`。

## 已执行结果

| 项目 | 结果 |
| --- | --- |
| 无线连接电视 | 成功 |
| `com.mitv.videoplayer/.VideoPlayerActivity` 接收 ACTION_VIEW + 本地 MP4 | Activity 成功启动，但提示播放失败 |
| 旧播放器错误 | 日志存在旧解码库加载失败记录和播放错误 1001/1；不能仅据此确定全部失败原因 |
| `com.mitv.mivideoplayer/com.mitv.videoplayer.VideoPlayerActivity` 接收相同测试文件 | 成功打开，截图确认显示动态测试素材 |
| 新播放器协议声明 | ACTION_VIEW 接受 content、file、http、https 视频地址；这只证明入口匹配，不代表各种来源均已实测 |
| YuanBaoTv 状态 | 打开系统播放器后，其原会话进入暂停状态 |

真实小爱暂停/继续已完成同步测试：在系统播放器显示测试视频后，停止自动控制，由用户对电视内置小爱说暂停、继续。用户明确反馈“暂停、继续都成功”。因此本机新版系统播放器的本地测试视频语音暂停/继续通过。快进、倍速、下一集未测试；不能仅凭旧 YuanBaoTv 会话状态判断外部播放器是否暂停。

## 后续实现边界

真实语音基础播控已验证成功，建议下一步评估 App 增加可选“系统播放器”入口，目标为 `com.mitv.mivideoplayer/com.mitv.videoplayer.VideoPlayerActivity`，保留原内置播放器。优先考虑使用新版播放器支持的 content URI 和临时读取授权，而不是向外部传递含凭据的 NAS URI。还需单独验证进度回传、字幕、选集、文件读取和退出行为。本次由 ADB 打开文件，尚未证明普通 App 发起外部播放的授权与回传链路可用。
