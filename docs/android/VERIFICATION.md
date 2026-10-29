# 验证记录

2026-10-29：问题报告修复后，21 项 Android JVM 回归测试与 3 项桌面备份 unittest 全部通过；`:app:assembleDebug` 构建成功。测试覆盖、命令和未验证边界见 [修复记录](ISSUE_FIXES_2026-10-29.md)。

## 2026-10-22 原生联网与订阅首轮

- 最终执行 `gradlew.bat :app:assembleDebug --offline --no-daemon --console=plain`，退出0，BUILD SUCCESSFUL in 25s；受影响原生 API、LLM、会话模块完成编译并打包诊断 Debug APK。
- 核对两端52条供应商 overlay 的有效 JSON 与重复标识、SIWC/Grok 协议/固定端点、联网高级配置与旧来源行为，Git 差异空白检查通过。
- 未新增测试框架、断言或门禁；未执行真实 ChatGPT Plus/Pro/Grok 登录、订阅扣额、供应商联网请求或设备回环/后台验收。当前额度服务返回 unsupported 与管理链接，不是已实现数字额度查询。
- 当前产物为 `app/build/outputs/apk/debug/app-debug.apk`，仍是诊断宿主，不代表完整产品前端完成。接口与剩余项见 [CORE_API.md](CORE_API.md)、[NATIVE_WEB_SUBSCRIPTION_PLAN.md](NATIVE_WEB_SUBSCRIPTION_PLAN.md)。

## 既有构建证据（2026-10-11）

整理前的最新记录：`:app:assembleDebug assembleBackendRelease --offline --no-daemon --console=plain` 最终 exit 0，BUILD SUCCESSFUL in 1m 29s；673 tasks / 44 executed / 629 up-to-date。

产物时间均为 Asia/Hong_Kong：

| 产物 | 字节 | 时间 |
| --- | ---: | --- |
| Debug APK | 15,319,988 | 11:21:08 |
| unsigned Release APK | 10,833,127 | 11:21:14 |
| memory Release AAR | 498,311 | 11:20:28 |
| import Release AAR（复用） | 346,695 | 11:13:17 |

聚合构建包含十个后端 AAR。它们是独立模块包，单个 api AAR 不包含其它模块和第三方依赖；源码接入与 JNI 要求见 [CORE_API](CORE_API.md)。诊断 APK 未签名为产品，不代表前端完成。

## 本次仓库整理

只合并文档、压缩重复历史、补充临时文件忽略规则，并删除旧构建日志及 build/diagnostics 的一次性 JVM 检查。生产源码、构建产物、SDK 配置、运行时资源与第三方源码/许可证保留。

未重新运行构建或测试。文档本地链接、Git 空白检查和清理范围在整理后核对；上面的成功记录属于整理前已执行构建。

## 未验证

实际用户包导入/恢复、真实上游调用、设备后台/锁屏、沙箱文件边界、崩溃与性能采样仍缺运行证据。原生正则没有硬超时。没有新增测试框架或测试套件；源码/静态产物检查不能代替这些运行验收。
