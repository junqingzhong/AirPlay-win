# AirPlay Server (Portable)

Windows 平台的 AirPlay 接收端，支持 iPhone/iPad 投屏到电脑。单 exe 文件，开箱即用，无需安装 Java、GStreamer 或 ffmpeg。

## ✨ 功能特性

- 📺 **视频投屏** — H.264 解码，GStreamer 渲染
- 🔊 **音频传输** — AAC-ELD / ALAC / AAC-LC 解码，Java Sound API 播放
- 📱 **音量同步** — 手机侧音量键可直接控制 Windows 端音量
- 🖥️ **横屏模式** — 画面自动旋转 90°，适合手机横屏投屏
- 📋 **控制台日志** — 实时显示连接状态、服务注册、音量变化等
- ⚙️ **配置保留** — 修改配置重启不丢失
- 🚀 **单文件部署** — 178MB exe 包含 JRE + GStreamer + ffmpeg，无需安装依赖

## 📥 下载

> `AirPlay-Server.exe`（约 178MB，含 JRE + GStreamer + ffmpeg）超过 GitHub 单文件 100MB 限制，请前往 [Releases 页面](../../releases) 下载。

下载后将 `AirPlay-Server.exe` 和 `application.properties` 放在同一目录即可使用。

## 📦 快速开始

1. 双击运行 `AirPlay-Server.exe`
2. 首次运行自动解压到 `%TEMP%\AirPlay-Server\`（约 10-30 秒）
3. 控制台窗口显示启动日志，系统托盘出现 AirPlay 图标
4. iPhone 控制中心 → 屏幕镜像 → 选择设备名
5. 投屏成功，画面 + 声音传输到电脑

## ⚙️ 配置说明

配置文件优先级（从高到低）：

| 优先级 | 位置 | 说明 |
|--------|------|------|
| 1 | exe 旁边的 `application.properties` | **推荐**，编辑后重启即生效 |
| 2 | `%TEMP%\AirPlay-Server\config\application.properties` | 上次运行的保留配置 |
| 3 | SFX 内置默认配置 | 兜底 |

常用配置项：

```properties
airplay.serverName=走马灯          # AirPlay 设备名称（支持中文）
airplay.width=1920                 # 视频宽度
airplay.height=1080                # 视频高度
airplay.fps=60                     # 帧率
airplay.landscape=false            # 横屏模式（true=旋转90°）
player.implementation=gstreamer    # 播放器：gstreamer/ffmpeg/vlc/h264-dump
player.gstreamer.swing=false       # GStreamer 是否用 Swing 窗口
```

## 🏗️ 项目结构

```
UxPlay-Portable/
├── AirPlay-Server.exe              # 最终打包的单文件 exe（178MB）
├── application.properties          # 用户配置（编辑此文件，重启生效）
├── 使用说明.txt                     # 中文使用说明
├── README.md                       # 本文件
├── .gitignore
│
├── source/                         # 源代码
│   ├── java-airplay/               # Java AirPlay 服务端源码（Gradle 项目）
│   │   ├── lib/                    # AirPlay 协议库（FairPlay, pairing, bonjour）
│   │   ├── server/                 # AirPlay 服务端（RTSP/HTTP handler, session）
│   │   ├── player/                 # 播放器实现
│   │   │   ├── app/                # Spring Boot 启动模块
│   │   │   ├── gstreamer/          # GStreamer 播放器（默认，音视频+横屏）
│   │   │   ├── ffmpeg/             # FFmpeg 播放器（备选，音频+ffplay视频）
│   │   │   ├── vlc/                # VLC 播放器
│   │   │   └── h264-dump/          # H.264 转储（调试用）
│   │   ├── client/                 # AirPlay 客户端（测试用）
│   │   ├── build.gradle            # 根构建脚本
│   │   └── settings.gradle
│   └── ffmpeg-fdk.exe              # ffmpeg（含 libfdk-aac，构建依赖，需单独下载）
│
└── build/                          # 构建脚本和工具
    ├── SfxStub.cs                  # C# 自解压存根源码
    ├── build-sfx.ps1               # SFX 打包 PowerShell 脚本
    └── config/
        └── application.properties  # SFX 内置默认配置
```

## 🔧 构建方式

### 前置条件

- JDK 17+（用于编译 Java 项目和 jlink 精简 JRE）
- Gradle 8.0+（项目内含 wrapper）
- .NET Framework 4.6.1+（用于编译 C# SfxStub）
- [GStreamer 1.28.4 MSVC x86_64](https://gstreamer.freedesktop.org/data/pkg/1.28.4/msvc/) 运行时
- ffmpeg（含 libfdk-aac 支持）

### 构建步骤

```bash
# 1. 编译 Java 项目
cd source/java-airplay
./gradlew clean bootJar

# 2. 复制 jar 到 build 目录
copy player\app\build\libs\java-airplay-server-1.0.8.jar ..\..\build\

# 3. 编译 C# SfxStub
cd ..\..\build
csc /target:exe /out:SfxStub.exe /lib:"C:\Program Files (x86)\Reference Assemblies\Microsoft\Framework\.NETFramework\v4.6.1" /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll /reference:System.Windows.Forms.dll SfxStub.cs

# 4. 打包 SFX exe
powershell -ExecutionPolicy Bypass -File build-sfx.ps1

# 5. 生成 AirPlay-Server.exe（约 178MB）
```

### SFX 结构

```
AirPlay-Server.exe = SfxStub.exe + "SFXZIP" + zip长度(4B LE) + payload.zip
```

运行时 SfxStub 从自身提取 payload.zip，解压到 `%TEMP%\AirPlay-Server\`，然后启动 `java.exe -jar`。

## 🎵 音频技术方案

```
iPhone → RTP → FairPlay AES 解密 → AppSrc → avdec_alac/avdec_aac
  → audioconvert → audioresample → PCM S16LE 44100Hz stereo
  → AppSink → Java Sound API (SourceDataLine) → 扬声器
```

- GStreamer 仅负责解码，不依赖 `autoaudiosink`（避免静默失败）
- 音频输出由 Java Sound API 的 `SourceDataLine` 处理
- 音量控制通过 `FloatControl.Type.MASTER_GAIN` 实现

## 📋 控制台日志

运行 exe 后控制台显示：

```
[AirPlay-Server] Using external config: ...\application.properties
[AirPlay-Server] Starting Java process...
[AirPlay-Server] --- Java logs below ---

  :: Spring Boot ::                (v3.3.6)

2026-07-07 ... INFO ... AirPlay control server listening on port: 59698
2026-07-07 ... INFO ... 走马灯._airplay._tcp.local service is registered
2026-07-07 ... INFO ... Started PlayerApp in 5.4 seconds
```

## 🛠️ 技术栈

| 组件 | 版本 |
|------|------|
| Spring Boot | 3.3.6 |
| Netty | 4.1.115.Final |
| Gradle | 8.0.2 |
| JDK | 17.0.19 (jlink 精简 JRE) |
| GStreamer | 1.28.4 (20 个精简插件) |
| ffmpeg | 8.0 (含 libfdk-aac) |
| JNA | 5.15.0 |
| jmdns | 3.5.12 |
| gst1-java-core | 1.4.0 |

## 📝 License

基于 [java-airplay](https://github.com/serezhka/java-airplay) 开源项目修改。

## 🔍 故障排除

| 问题 | 解决方案 |
|------|----------|
| 手机找不到设备 | 同一 WiFi，检查防火墙，关闭 VPN |
| 有画面无声音 | 确认 gstreamer 模式，查看日志是否有 "audio pipeline started" |
| 音量控制无效 | 查看日志是否有 "AirPlay volume change" |
| 修改配置不生效 | 编辑 exe 旁的 application.properties，重启 |
| 画面方向错误 | 修改 airplay.landscape=true/false |

详见 [使用说明.txt](使用说明.txt)。
