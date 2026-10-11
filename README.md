# SimpleTavern Android

原生 Android 的 Kotlin 业务核心：Room 存储、聊天与记忆、来源导入、助手工具、多沙箱、语音和后台任务。当前 app 是诊断宿主，产品界面尚未实现。

## 构建

需要 JDK 17、Android SDK 35 与 Build Tools 35；AGP 8.7.3、Gradle 8.11.1、Kotlin 2.0.21。按 `local.properties.example` 设置本机 SDK 路径。首次构建需下载依赖，缓存齐全后可离线执行：

```powershell
.\gradlew.bat :app:assembleDebug --offline --no-daemon --console=plain
.\gradlew.bat assembleBackendRelease --offline --no-daemon --console=plain
```

Release 聚合生成十个 AAR 与未签名诊断 APK；模块依赖、JNI 与宿主接入见 [接口文档](docs/android/CORE_API.md)。已有构建通过，真机和实际数据验收仍待完成。

## 文档

- [当前状态与限制](docs/android/STATUS.md) · [验证记录](docs/android/VERIFICATION.md) · [验收清单](docs/android/ACCEPTANCE.md)
- [公开接口](docs/android/CORE_API.md) · [数据需求与映射](docs/android/DATA_MODEL.md) · [原生备份格式](docs/android/NATIVE_BACKUP_V1.md)
- [聊天与媒体](docs/android/CHAT.md) · [提供商与模型](docs/android/PROVIDERS.md) · [Gemini/Vertex 缓存](docs/android/GEMINI_CACHE.md)
- [助手与工具](docs/android/ASSISTANT.md) · [语音](docs/android/SPEECH.md) · [用量与搜索](docs/android/ACCOUNTING.md) · [沙箱](docs/android/SANDBOX.md)

`docs/specs/` 与知识图谱/分支需求为桌面行为参考；本地参考源码在同级 `SimpleTavern-Desktop`。第三方来源与许可说明保留在 `third_party/`。
