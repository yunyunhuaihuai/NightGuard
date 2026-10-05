# AGENTS.md — 通知唤醒（NightGuard）项目 AI 协作入口

> 给接手本项目的 AI Agent：先读本文件，再按需读 `交付文档.md`（状态/问题/路线图）
> 与 `技术实现.md`（架构/数据模型/系统机制）。改动代码前必读两者的"已知坑"。

## 一句话

Android 15（API 35）应用"通知唤醒"：监听时段内收到指定应用的消息或指定联系人的来电时，
"N 分钟后响铃闹钟"。事件驱动、零轮询、无 INTERNET 权限。
目标设备：OnePlus 13（PJZ110，ColorOS 15）。当前版本 v0.7.0（versionCode 9）。

## 当前状态（2026-10-05）

- **稳定性专项三阶段已完成**（分支 `agent/stability-hardening`，提交 d9e8d0c / 9078342 /
  cf504ba）：①确定性缺陷修复（MessagingStyle 解析、来电候选日志脱敏、响铃会话生命周期）
  ②统一待响任务生命周期（PendingTask 状态机、taskId 广播验真、接听事件化、幂等恢复）
  ③数据一致性/线程模型/界面状态（Store 事务 API、goAsync/协程化、连接四态、Flow、草稿）
- **51 个单元测试全绿**（`./gradlew :app:testDebugUnitTest`，JUnit+Robolectric）；
  assembleDebug / lintDebug 通过（0 error）
- ⚠️ 本次会话**无 adb 设备连接**：三阶段改动**尚未真机回归**，待验证清单见
  `交付文档.md` §2.2——不得宣称真机回归通过
- 历史真机结论（v0.5.0 时期）：触发→响铃→取消闭环、ColorOS 两个限制均已验证（见下"必读坑"）

## 环境速查（Windows + Git Bash）

| 项 | 值 |
|---|---|
| 项目根 | `D:\APK\NightGuard` |
| JDK | Temurin 21（JAVA_HOME 已配） |
| SDK | `D:\AndroidStduio\Sdk`（platforms 35/36/37，build-tools 35/37） |
| Gradle | 8.13（GRADLE_USER_HOME=`D:\AndroidStduio\GradleHome`，依赖已缓存） |
| AGP/Kotlin | 8.11.1 / 2.0.21（均有缓存）；测试框架 JUnit 4.13.2 / Robolectric 4.14.1（已缓存） |
| 网络 | `dl.google.com` 直连不稳 → `settings.gradle.kts` 已配阿里云 google 镜像（google()/mavenCentral() 兜底）；Maven Central 可达（Robolectric android-all 已下载） |
| adb | `D:\AndroidStduio\Sdk\platform-tools\adb.exe`（Git Bash 里跑 adb 时加 `export MSYS_NO_PATHCONV=1` 防路径改写） |
| 构建 | 项目根 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` |
| 设备 | OnePlus 13，序列号 e4e1b43e，adb 调试已开（**本次会话未连接**） |

## 必读坑（都是真机踩出来的，勿重蹈）

1. **ColorOS 拦截第三方改勿扰（"动作一"已整体移除）**：`setInterruptionFilter` 不抛异常
   但不生效，由 `systemui|DndAlertHelper` 转用户确认框，每次修改都要确认且不记忆。
   开发者选项"禁止权限监控"也无法绕过。若未来 ColorOS 放开此限制，从 git/文档历史
   （技术实现.md §5.1）可找回原实现思路。
2. **应用更新（install -r）后监听器可能"已授权但未连接"**：收不到任何通知。
   修复：`adb shell cmd notification allow_listener com.nightguard.app/.service.NightNotificationListener`，
   或应用内重新开关通知使用权（应用 onResume 已自动 requestRebind）。
   权限页现按当前进程连接状态显示四态（未授权/已授权未连接/已连接/状态未知）。
3. **重连后第一条通知可能丢**（系统竞态）：测试时连发两条，第二条才进回调。
4. **`cmd notification allow_dnd` 在 ColorOS 无效**（exit 0 但没真授权）；
   shell 的 `pm grant` / `cmd appops set` 都被 ROM 拒。运行时权限只能 UI 里授。
5. **设备 logcat 缓冲几分钟就翻转**：抓证据要么及时 `logcat -d`，要么 `logcat -c` 后
   复现立即抓。
6. **读应用私有数据**：debug 包可 `adb shell run-as com.nightguard.app cat
   files/datastore/nightguard.preferences_pb`，内容是 protobuf，`tr -d '\0'` 后可 grep
   （注意 `grep -a`，且日志数组**最新在前**，验证用 `head` 别用 `tail`）。
   任务数据在 `pendingTasks` 键（旧 `pendingAlarm` 键已被一次性迁移删除）。
7. **ZenMode 历史日志**：`dumpsys notification | grep -E "^    2026"`（时间戳约 2 小时偏移，
   只用于相对顺序）。
8. **Git Bash 的 /tmp 与 grep 路径解析不一致**：临时文件放项目目录（如 `_test/`）。
   `_test/` 是 gitignore 的本机测试现场（可能含个人数据快照），**不要读取/复制/提交**。

## 测试入口

- **单元测试**（改代码后必跑）：`./gradlew :app:testDebugUnitTest`，报告在
  `app/build/reports/tests/testDebugUnitTest/`。窗口边界、MessagingStyle 提取、日志隐私、
  任务状态机（并发/广播验真/恢复）、Store 事务并发等均有回归用例。
- **lint**：`./gradlew :app:lintDebug`（当前 0 error；14 条既有 warning 属提示类别）。
- 应用内“权限”页底部有**自检工具**：①创建自检规则并触发 ②真实通知指引
  ④清除自检规则（监听 `com.android.shell` 的通知，配合 `adb shell cmd notification
  post -t 自检测试 shelltag "你好"` 端到端测试；自检规则只含延迟响铃，会出声，测完用④清理）
- 状态检查：`dumpsys alarm | grep nightguard`（DELAYED/HEARTBEAT）、
  `dumpsys activity service com.nightguard.app/.service.NightNotificationListener`
  （注意 bound 服务可能显示旧记录，以日志与应用内四态状态为准）
- 日志页事件标签语义：装载/跳过/装载失败/开始响铃/响铃失败/取消/抑制/停止/恢复
  （“装载”≠“响了”，看“开始响铃”）

## 约定

- 改完代码必须真机回归：①自检触发 → 响铃 → 自动停止/取消 → 日志闭环；
  设备不可用时如实列明待验证项，**不得把代码推断或单测写成真机验证结果**
- 无 INTERNET 权限是安全底线，不得添加任何联网能力
- 不引入 / 保留 CallScreeningService（用户明确不要接管系统骚扰拦截）
- 不恢复自动修改勿扰功能（ColorOS 系统限制，动作一已移除）
- 中文 UI 文案；文档用中文
- 提交前跑 `testDebugUnitTest + assembleDebug + lintDebug`，失败不交付
