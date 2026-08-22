# Figma 页面任务卡：圆宝TV - 全屏播放器 (PlayerActivity)

## 一、基本信息

- **页面名称**：圆宝TV - 全屏播放器 (Player)
- **Figma 文件**：圆宝TV
- **Figma Frame Node ID**：`1:300`
- **Frame 尺寸**：1280 × 720 (标准 TV 16:9 全屏)
- **Figma 逻辑屏幕宽度**：1280px
- **Android 基准宽度**：1280dp
- **换算公式**：`dp = px * 1.0` (比例 1:1)
- **所属模块**：`feature:feature_app`
- **公共资源模块**：`feature:feature_res`
- **页面路由**：`com.wkq.bao.feature.app.PlayerActivity`
- **Activity 基类**：`com.wkq.base.activity.BaseActivity<ActivityPlayerBinding>`
- **生命周期与系统栏规范**：`hideSystemUi()` 沉浸式隐藏系统栏，`keepScreenOn="true"` 保持屏幕常亮

---

## 二、页面图层与结构分解 (Hierarchy)

```text
根容器: ConstraintLayout (全黑背景, match_parent)
├── [Layer 0] player_view (Media3 PlayerView, 0dp x 0dp 铺满全屏, resize_mode="fit", use_controller="false")
└── [Layer 1] layout_osd (OSD 交互覆盖层, #99000000 60% 渐变黑幕, padding=36dp, 5s 自动隐藏)
      ├── [顶部栏] layout_top_bar (height=wrap_content, top 对齐)
      │     ├── tv_title (当前剧集与单集标题, 22sp Bold, 如 "汪汪队立大功 - S01E03")
      │     ├── tv_source_badge (播放来源微型徽章, "本地离线" / "NAS 局域网")
      │     └── tv_clock (右侧当前系统时钟, 如 "16:00", 14sp)
      └── [底部控制区] layout_bottom_panel (bottom 对齐, 垂直排列)
            ├── [时间与进度栏] layout_progress_bar (水平排布, height=32dp)
            │     ├── tv_current_time (当前已播放时长, 13sp, 如 "08:45")
            │     ├── seek_progress (全宽 TV SeekBar, 1000 级, 获焦青色呼吸高亮)
            │     └── tv_total_time (总时长, 13sp, 如 "24:10")
            └── [遥控器快捷控制组] layout_action_buttons (水平居中排布, marginTop=14dp)
                  ├── btn_rewind (快退 10 秒, 84dp x 40dp, @drawable/bg_tv_button_focus)
                  ├── btn_play_pause (播放/暂停主按键, 100dp x 40dp, 青色高亮)
                  ├── btn_fast_forward (快进 10 秒, 84dp x 40dp)
                  ├── btn_speed (倍速调节, 84dp x 40dp, 0.5x ~ 2.0x 循环)
                  └── btn_next_episode (下一集, 100dp x 40dp, 跨季自动衔接)
```

---

## 三、关键图层到 Android XML 映射表

| Figma 图层名 | Node ID | Android 控件 ID | 控件类型 | 尺寸 (dp) | 样式与规范 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Video Surface | `1:301` | `player_view` | `PlayerView` | `0dp x 0dp` (全屏) | `app:use_controller="false"` |
| OSD Overlay | `1:302` | `layout_osd` | `ConstraintLayout` | `0dp x 0dp` (全屏) | `#99000000` 半透明遮罩 |
| Title Text | `1:305` | `tv_title` | `TextView` | `wrap_content` | 22sp, `#FFFFFF`, Bold |
| Playback Badge| `1:308` | `tv_source_badge` | `TextView` | `wrap_content` | `@drawable/bg_badge_local` / `nas` |
| Current Time | `1:315` | `tv_current_time`| `TextView` | `wrap_content` | 13sp, `#FFFFFF` |
| Progress Bar | `1:318` | `seek_progress` | `SeekBar` | `0dp x 24dp` | `progressTint="@color/tv_accent_cyan"` |
| Total Time | `1:322` | `tv_total_time` | `TextView` | `wrap_content` | 13sp, `@color/tv_text_secondary` |
| Rewind Button | `1:330` | `btn_rewind` | `Button` | `84dp x 40dp` | `@drawable/bg_tv_button_focus` |
| Play/Pause | `1:335` | `btn_play_pause` | `Button` | `100dp x 40dp` | `@drawable/bg_hero_play_btn` |
| Fast Forward | `1:340` | `btn_fast_forward`| `Button` | `84dp x 40dp` | `@drawable/bg_tv_button_focus` |
| Speed Button | `1:345` | `btn_speed` | `Button` | `84dp x 40dp` | `@drawable/bg_tv_button_focus` |
| Next Episode | `1:350` | `btn_next_episode`| `Button` | `100dp x 40dp`| `@drawable/bg_tv_button_focus` |

---

## 四、遥控器 D-Pad 与手机横屏手势合同

1. **D-Pad 键位映射**：
   - **OK / Enter 键**：若 OSD 处于显示状态则隐藏 OSD；若隐藏则呼出 OSD 并聚焦在 `btn_play_pause`。
   - **Left / Right 键**：单次点击快退/快进 10 秒，并展示 OSD 进度。
   - **Play / Pause 键**：切换播放与暂停状态。
   - **Back 键**：若 OSD 显示则优先关闭 OSD，若 OSD 已隐藏则退出播放器。
2. **自动淡出机制**：
   - OSD 呼出后启动 5 秒无操作自动隐藏倒计时；任何遥控器按键或触控事件重置倒计时。
3. **断点续播与历史记录**：
   - 播放时每隔 10 秒向 `WatchHistoryDao` 持久化一次当前毫秒进度；进入时自动恢复上次未完成播放点。

---

## 五、门禁审计结论

- [x] **架构审计**：采用 `BaseActivity<ActivityPlayerBinding>`
- [x] **安全区与全屏**：采用 `WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 沉浸式
- [x] **当前状态**：**分析完成（figma-process 完成），已输出施工图任务卡。当前已停止修改代码，等待用户确认！**
