# AGENTS.md — 通知唤醒（NightGuard）项目 AI 协作入口

> 给接手本项目的 AI Agent：先读本文件，再按需读 `交付文档.md`（状态/问题/路线图）
> 与 `技术实现.md`（架构/数据模型/系统机制）。改动代码前必读两者的"已知坑"。

## 一句话

Android 15（API 35）应用"通知唤醒"：监听时段内收到指定应用的消息或指定联系人的来电时，
自动"关闭勿扰+响铃"和/或"N 分钟后响铃闹钟"。事件驱动、零轮询、无 INTERNET 权限。
目标设备：OnePlus 13（PJZ110，ColorOS 15）。

## 当前状态（2026-09-13）

- 五条链路全部真机验证通过（详见 交付文档.md 的测试记录）
- 两个 ColorOS 系统限制已定位并适配（勿扰确认弹窗、监听器重连），见下"必读坑"
- 测试现场已清理，只保留用户自己的"起床"规则

## 环境速查（Windows + Git Bash）

| 项 | 值 |
|---|---|
| 项目根 | `D:\APK\NightGuard` |
| JDK | Temurin 21（JAVA_HOME 已配） |
| SDK | `D:\AndroidStduio\Sdk`（platforms 35/36/37，build-tools 35/37） |
| Gradle | 8.13（GRADLE_USER_HOME=`D:\AndroidStduio\GradleHome`，依赖已缓存） |
| AGP/Kotlin | 8.11.1 / 2.0.21（均有缓存） |
| adb | `D:\AndroidStduio\Sdk\platform-tools\adb.exe`（Git Bash 里跑 adb 时加 `export MSYS_NO_PATHCONV=1` 防路径改写） |
| 构建 | 项目根 `./gradlew :app:assembleDebug` |
| 设备 | OnePlus 13，序列号 e4e1b43e，adb 调试已开 |

## 必读坑（都是真机踩出来的，勿重蹈）

1. **ColorOS 拦截第三方改勿扰（已导致"动作一"整体移除）**：`setInterruptionFilter`
   不抛异常但不生效，由 `systemui|DndAlertHelper` 转用户确认框，**每次修改都要确认且
   不记忆**。实测开启开发者选项"禁止权限监控"（2026-09-14）也无法绕过。勿扰权限
   设置页里本应用显示"锁定开启"属正常，没有开关可拨。若未来 ColorOS 放开此限制，
   从 git/文档历史（技术实现.md §5.1）可找回原实现思路。
2. **应用更新（install -r）后监听器可能"已授权但未连接"**：收不到任何通知。
   修复：`adb shell cmd notification allow_listener com.nightguard.app/.service.NightNotificationListener`，
   或应用内重新开关通知使用权（应用 onResume 已自动 requestRebind）。
3. **重连后第一条通知可能丢**（系统竞态）：测试时连发两条，第二条才进回调。
4. **`cmd notification allow_dnd` 在 ColorOS 无效**（exit 0 但没真授权）；
   shell 的 `pm grant` / `cmd appops set` 都被 ROM 拒（缺 GRANT_RUNTIME_PERMISSIONS /
   MANAGE_APP_OPS_MODES）。运行时权限只能 UI 里授。
5. **设备 logcat 缓冲几分钟就翻转**：抓证据要么及时 `logcat -d`，要么 `logcat -c` 后
   复现立即抓。
6. **读应用私有数据**：debug 包可 `adb shell run-as com.nightguard.app cat
   files/datastore/nightguard.preferences_pb`，内容是 protobuf，`tr -d '\0'` 后可 grep
   （注意 `grep -a`，且日志数组**最新在前**，验证用 `head` 别用 `tail`）。
7. **ZenMode 历史日志**：`dumpsys notification | grep -E "^    2026"` 可看勿扰修改的
   调用者与结果（时间戳与手机时钟约有 2 小时偏移，只用于相对顺序）。
8. **Git Bash 的 /tmp 与 grep 路径解析不一致**：临时文件放项目目录（如 `_test/`）。

## 测试入口

- 应用内“权限”页底部有**自检工具**：①创建自检规则并触发 ②真实通知指引
  ④清除自检规则（监听 `com.android.shell` 的通知，配合 `adb shell cmd notification
  post -t 自检测试 shelltag "你好"` 可端到端测试；自检规则只含延迟响铃，会出声，
  测完用④清理）
- 状态检查：`settings get global zen_mode`（0=off 1=priority）、
  `dumpsys alarm | grep nightguard`、`dumpsys activity service
  com.nightguard.app/.service.NightNotificationListener`（注意 bound 服务可能显示
  旧记录，以日志为准）

## 约定

- 改完代码必须真机回归：①自检触发 → 响铃 → 自动恢复 → 日志闭环
- 无 INTERNET 权限是安全底线，不得添加任何联网能力
- 不引入 / 保留 CallScreeningService（用户明确不要接管系统骚扰拦截）
- 中文 UI 文案；文档用中文
