# VERIFICATION

日期：2026-09-30

## 构建

| 项 | 值 |
|----|----|
| 命令 | `gradlew.bat :app:assembleDebug`（计划） |
| JDK | D:\JDK17（17.0.12） |
| Android SDK | **当时本机未安装 / 未配置** |
| 结果 | 见下文实际执行记录 |

## 未验证

- 真机后台/锁屏继续  
- 骁龙 8 Elite 性能采样  
- 用户桌面完整包 / RikkaHub 用户版本备份  
- PRoot 隔离越界用例  
- 四协议真实上游流式  

## 证据策略

构建日志写入本文件「实际执行」节；设备日志与采样另附路径。不得复制未执行的成功记录。

## 实际执行

| 项 | 结果 |
|----|------|
| 命令 | `tools\_build.bat` → `gradlew.bat :app:assembleDebug --no-daemon` |
| JDK | `D:\JDK17`（17.0.12） |
| SDK | `D:\Android\Sdk`（platforms;android-35，build-tools;35.0.0） |
| 结果 | **BUILD SUCCESSFUL**（约 28s，增量；首次含 Gradle 分发包下载约 15m） |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk` |
| 日志 | 仓库根 `build_log.txt`（本地构建痕迹，可不入库） |
| 自动化测试 | **未运行**（按用户硬要求：不准搞一堆测试；本轮无新增测试文件） |

未在本机执行真机安装、导入样本或沙箱越界实测。
