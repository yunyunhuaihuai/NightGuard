# 通知唤醒（NightGuard）

**深夜重要消息/来电，别再漏接。** Android 本地应用：在你设定的监听时段内，收到
指定应用的消息或指定联系人的来电时，延迟 N 分钟走**系统闹钟流**响铃唤醒你——
勿扰模式下也能响，醒着的人可在通知上一键取消，睡着的人正常被叫醒。

> 为“睡前怕错过家人/值班消息，又怕闹钟吵醒全家”的场景设计。
> 针对 OnePlus 13（ColorOS 15）真机调优，兼容 Android 14+（minSdk 34）。

## 特性

- **事件驱动、零轮询、零常驻服务**：触发依赖系统推送（通知监听 + 精确闹钟），省电且不易被杀
- **消息唤醒**：按应用包名 + 可选关键词匹配（支持标准 MessagingStyle 通知的发送者/正文解析）
- **来电唤醒**：按联系人姓名/号码匹配拨号器来电通知；**已接听则自动取消闹钟**
  （基于通话状态事件，快速接听也能取消；双卡、权限撤销、进程重建均有兜底）
- **醒着可取消**：定时成功先发一条静默通知——“本次不响”（只取消这次）/“今天不再响”
  （本窗口内不再触发，跨午夜/全天边界统一处理）
- **可靠的生命周期管理**：
  - 持久化待响任务 + taskId 广播验真——旧广播不会误触发/误取消新任务，取消过的任务不会复活
  - 重启/应用更新后幂等恢复（宽限期内补响，超期明确跳过并记录）
  - 响铃页与响铃会话绑定：超时/停止/失败后自动退出，不留常亮屏幕
  - 并发触发同一条规则只产生一个有效任务；调度失败有明确记录
- **可诊断**：日志区分 装载 / 跳过 / 装载失败 / 开始响铃 / 响铃失败 / 取消 / 抑制 /
  停止 / 恢复——“定时成功”和“真的响了”分得清
- **界面状态还原**：规则/日志实时刷新，编辑草稿切页、旋转、进程重建都不丢

## 隐私

- **无 INTERNET 权限**——物理断网，不依赖“我们不上传”的承诺
- 通知内容只在内存中做关键词匹配，**不落盘**
- 触发日志只记规则名/来源/动作等元数据；来电通知原文（可能含完整号码）不进本地日志
- 不使用 CallScreeningService（不接管系统骚扰拦截），不修改勿扰状态

## 权限清单

| 权限 | 用途 |
|---|---|
| 通知使用权（NotificationListener） | 核心：读取目标应用消息与拨号器来电通知 |
| POST_NOTIFICATIONS | 展示响铃/静默提醒/状态通知 |
| USE_EXACT_ALARM | 准点延迟响铃（系统自动授予；被关闭时明确提示“不保证准点”） |
| READ_CONTACTS（可选） | 来电按联系人匹配（仅本机） |
| READ_PHONE_STATE（可选） | 接听后自动取消闹钟（仅读通话状态） |
| FOREGROUND_SERVICE(_SHORT_SERVICE) | 响铃前台服务（≤60 秒，系统强制超时兜底） |
| RECEIVE_BOOT_COMPLETED / WAKE_LOCK / VIBRATE / USE_FULL_SCREEN_INTENT / 请求电池白名单 | 重排闹钟 / 保 CPU / 震动 / 锁屏亮屏（可选）/ 防 ROM 清后台 |

## 构建

要求：JDK 17+（项目按 17 编译，验证用 Temurin 21）、Android SDK 35。

```bash
git clone https://github.com/yunyunhuaihuai/NightGuard.git
cd NightGuard
./gradlew :app:assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 51 个单元测试（JUnit + Robolectric）
./gradlew :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

国内网络备注：`settings.gradle.kts` 已配置阿里云 google 镜像（google()/mavenCentral() 兜底）。

安装后请重新授予“通知使用权”（系统在应用更新后可能不自动重绑）：
应用内开关一次，或
`adb shell cmd notification allow_listener com.nightguard.app/.service.NightNotificationListener`

## 验证状态

- ✅ **真机实测**（OnePlus 13 / ColorOS 15，v0.5.0 时期）：真实通知触发 → 延迟响铃 →
  手动停止 / 60 秒自动停；通知上“本次不响”取消成功；闹钟流穿透勿扰
- ✅ **自动化验证**（v0.7.0）：51 个单元测试全绿（时段窗口边界、MessagingStyle 解析、
  日志隐私、任务去重与广播防误触发、重启恢复策略、并发修改规则不互相覆盖等）；
  构建 + lint 通过
- ⏳ **待真机回归**（v0.5.1–v0.7.0 改动）：真实来电接听自动取消（快速接听/拒接/双卡）、
  锁屏/Doze 表现、重启恢复的实机表现——清单见 `交付文档.md` §2.2

## 已知限制

- **ColorOS 15 禁止第三方自动修改勿扰**（系统级确认机制，无法绕过）：本应用不修改勿扰，
  唤醒依赖闹钟流穿透勿扰（实测有效）
- 重连监听后第一条通知可能因系统竞态丢失（应用已有自愈，日常几乎无感）
- 精确闹钟被用户关闭时退化为窗口闹钟，应用会明确标注“不保证准点”

## 文档

- [使用说明.md](使用说明.md) — 用户视角：配置、自检工具、日志怎么读
- [技术实现.md](技术实现.md) — 架构、数据模型、任务状态机、ColorOS 实测结论
- [交付文档.md](交付文档.md) — 验证状态、已知问题、路线图
- [AGENTS.md](AGENTS.md) — AI/开发者协作入口与环境速查

## 目录

```
app/src/main/java/com/nightguard/app/
├── data/       规则/日志/待响任务模型 + DataStore（事务 API、Flow、迁移）
├── logic/      规则引擎 · 装载编排 · 闹钟调度 · 接听事件监听 · 恢复编排
├── service/    通知监听(NLS) · 响铃前台服务(shortService) · 响铃页
├── receiver/   到期验真/心跳/开机恢复（goAsync）
└── ui/         Compose 三页（权限/规则/日志）
app/src/test/   单元测试（JVM + Robolectric）
```
