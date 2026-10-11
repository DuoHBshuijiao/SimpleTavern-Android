# 验证记录

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
