# AirPlay 源码审查与优化报告

> 审查对象：`java-airplay-server.jar`（serezhka/airplay-server v1.0.6，Spring Boot 3.0.0 fat jar）
> 审查方式：反编译字节码（jar 内仅含 `.class`，无 `.java` 源码）
> 审查日期：2026-07-06

## 一、项目现状

- **项目**：`com.github.serezhka.airplay`（开源 AirPlay 接收端，Spring Boot 应用）
- **版本**：1.0.6（最后构建约 2022 年，项目已停止积极维护）
- **入口**：`com.github.serezhka.airplay.app.PlayerApp`（`SpringApplicationBuilder.web(NONE).headless(false).run()`）
- **模块**：`lib-1.0.6`（协议：Bonjour/Pairing/FairPlay/RTSP）、`server-1.0.6`（Netty 控制/视频/音频服务器）、四个可插拔播放器：`gstreamer` / `vlc` / `ffmpeg` / `h264-dump`
- **配置**：`application.properties`（`player.implementation=gstreamer` 为默认，含 H.264 视频 + ALAC/AAC-ELD 音频解码）

## 二、兼容性问题

| 问题 | 影响 | 处理 |
|------|------|------|
| Spring Boot 3.0.0 已 EOL（2023-11 停止 OSS 支持） | 无安全补丁 | 无源码无法升级；通过启动器加固缓解 |
| Netty 4.1.85.Final 存在多个后续 CVE（HTTP/2 Rapid Reset CVE-2023-44487 等） | 网络面 DoS 风险 | 无源码无法升级；监听仅限局域网 |
| BouncyCastle 1.66（2020）有 CVE-2020-28052 等 | 证书解析风险 | 仅用于 FairPlay 协议，非外部证书输入 |
| 配对的 `pk`（广告的公钥）与运行时生成的 Ed25519 密钥不匹配 | 严格校验的 iOS 客户端配对失败 | 已知遗留问题，宽松客户端可正常工作 |
| `GstPlayerUtils.configurePaths()` 依赖 `GSTREAMER_1_0_ROOT_MSVC_X86_64` 等环境变量 | 未安装 GStreamer 时启动崩溃 | **启动器自动设置环境变量** |
| `FFmpegPlayer` 调用外部 `ffplay`，依赖 PATH | 找不到 ffplay 则视频无法播放 | **启动器自动把 ffmpeg/bin 加入 PATH** |
| Java 17 JRE 依赖 | 用户需自带 JRE | **打包内嵌 JRE** |

## 三、安全漏洞

1. **开放网络监听（高危，但为 AirPlay 功能固有）**：四个 Netty 服务器均绑定 `0.0.0.0`（ControlServer:5001/TCP，VideoReceiver/AudioReceiver/AudioControlServer:动态端口）。无密码、无 TLS。任何同局域网设备均可投屏。
   - 缓解：仅在使用时运行；置于可信网络；不要暴露到公网。
2. **硬编码设备身份**：Bonjour 广告中 `deviceid=01:02:03:04:05:06`、`pi`、`pk`、`model=AppleTV3,2`、`features=0x5A7FFFE4,0x1E`。每台机器广告相同 MAC（多台接收端同网会冲突）。
3. **FairPlay 反向工程密钥表**（`table_s1`…`table_s10`）内置于 jar；属协议实现必需，非凭据泄露。
4. **不可信输入反序列化面**：RTSP SETUP body 用 `dd-plist-1.26` 解析二进制 plist（来自未认证 socket）；jackson/avro/snakeyaml 存在但 Spring Boot 用 `SafeConstructor` 且 `WebApplicationType.NONE`，常规反序列化攻击面基本不暴露。
5. **无 Log4Shell 风险**：仅含 `log4j-api`/`log4j-to-slf4j` 桥接，无 `log4j-core`。

## 四、性能瓶颈

- `RTSPHandler` 的 SETUP 音频分支用 `Object.wait()/notify()` 等待 UDP 服务器端口（每会话阻塞）。
- `AudioHandler` RTP 重排序用数组线性扫描（小包缓冲，影响可忽略）。
- GStreamer 管线 `sync=false`（音频）追求低延迟；视频管线由子类定义。
- 无源码，无法做字节码级性能优化；通过启动器确保本地库路径正确、避免 JNA 反复搜索路径来减少启动开销。

## 五、未修复的功能缺陷

- 配对公钥不匹配（见上）——严格 iOS 客户端可能无法配对。
- `RTSPHandler` 视频事件端口硬编码 `7011`。
- `VlcPlayer`/`FFmpegPlayer` 不处理音频（onAudio 空实现）。
- 仅 `gstreamer` 播放器支持完整音视频。

## 六、本次优化措施（无源码条件下的可行范围）

1. **启动器加固（核心）**：用 C# 编写原生启动器 `AirPlayServer.exe`：
   - 自动设置 `GSTREAMER_1_0_ROOT_MSVC_X86_64` 指向内嵌 GStreamer
   - 把 `gstreamer\bin`、`ffmpeg\bin`、`jre\bin` 加入 `PATH`
   - 定位内嵌 `java.exe` 与 jar，以正确工作目录启动
   - 捕获异常、写日志、转发退出码、控制台标题
2. **配置加固**：重写 `application.properties`，调整日志级别、设备名、分辨率，启用 `player.fallback=ffmpeg`。
3. **环境自包含**：内嵌 JRE 17、ffmpeg、GStreamer 运行时，无需用户预装。
4. **打包为单一自解压 exe**：IExpress SFX，运行时解压到 `%TEMP%\AirPlay-Server` 并启动，对用户表现为"双击即用的单文件"。
5. **不可改动的部分**（无源码）：Spring Boot/Netty/BC 版本、字节码逻辑、配对密钥不匹配。

## 七、无法完成项 / 需用户知悉

- **无 `.java` 源码**：jar 内仅编译字节码，无法做源码级修复或依赖大版本升级。如需深度修复，需从 GitHub 取 `serezhka/airplay-server` 源码并用 JDK 17 重新构建。
- **iOS 真机测试**：本环境无 iOS 设备，仅能验证服务启动、端口监听、Bonjour 广告；实际投屏需用户用 iPhone 验证。
- **"单 exe" 含义**：Java + GStreamer + ffmpeg 技术栈无法编译为静态原生二进制；本方案为自解压 SFX（仍是单一 exe 文件，双击即运行，无外部依赖）。
