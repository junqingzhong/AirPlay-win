// SfxStub.cs - AirPlay-Server 自解压存根
// 编译: csc /target:exe /out:SfxStub.cs /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll SfxStub.cs
// 结构: [SfxStub.exe][SFXZIP][zip长度4字节LE][zip数据]
using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;

class SfxStub
{
    private const string Marker = "SFXZIP";
    private const string AppDirName = "AirPlay-Server";

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Auto)]
    static extern bool SetEnvironmentVariable(string lpName, string lpValue);

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool SetConsoleOutputCP(uint wCodePageID);

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool SetConsoleCP(uint wCodePageID);

    static int Main(string[] args)
    {
        try
        {
            // 设置控制台为 UTF-8 编码，让中文 serverName 等正确显示
            SetConsoleOutputCP(65001);
            SetConsoleCP(65001);
            // 1. 读取自身 exe 文件
            string exePath = Process.GetCurrentProcess().MainModule.FileName;
            byte[] exeBytes = File.ReadAllBytes(exePath);

            // 2. 搜索 SFXZIP marker（在前 64KB 内）
            byte[] markerBytes = Encoding.ASCII.GetBytes(Marker);
            int markerPos = -1;
            int searchLimit = Math.Min(exeBytes.Length, 65536);
            for (int i = 0; i < searchLimit - markerBytes.Length; i++)
            {
                bool match = true;
                for (int j = 0; j < markerBytes.Length; j++)
                {
                    if (exeBytes[i + j] != markerBytes[j])
                    {
                        match = false;
                        break;
                    }
                }
                if (match)
                {
                    markerPos = i;
                    break;
                }
            }

            if (markerPos < 0)
            {
                MessageBox.Show("SFXZIP marker not found. This is not a valid SFX archive.",
                    "AirPlay-Server Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }

            // 3. 读取 zip 长度（marker 后 4 字节，little-endian）
            int zipStart = markerPos + markerBytes.Length;
            if (zipStart + 4 > exeBytes.Length)
            {
                MessageBox.Show("Invalid SFX archive: missing zip length.",
                    "AirPlay-Server Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
            int zipLength = BitConverter.ToInt32(exeBytes, zipStart);
            int zipDataStart = zipStart + 4;

            if (zipDataStart + zipLength > exeBytes.Length)
            {
                MessageBox.Show("Invalid SFX archive: zip data truncated.",
                    "AirPlay-Server Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }

            // 4. 解压到 %TEMP%\AirPlay-Server\
            string tempDir = Path.GetTempPath();
            string appDir = Path.Combine(tempDir, AppDirName);
            string configPath = Path.Combine(appDir, @"config\application.properties");

            // 配置优先级（从高到低）：
            //   1. exe 旁边的 application.properties（用户可直接编辑，最直观）
            //   2. 之前解压目录中保留的配置（临时目录中的上次修改）
            //   3. SFX 内置的默认配置
            string exeDir = Path.GetDirectoryName(exePath);
            string externalConfig = Path.Combine(exeDir, "application.properties");
            bool hasExternalConfig = File.Exists(externalConfig);

            // 保留用户已修改的配置文件（serverName、landscape 等），避免重启后丢失
            byte[] savedConfig = null;
            if (File.Exists(configPath))
            {
                try
                {
                    savedConfig = File.ReadAllBytes(configPath);
                    Console.WriteLine("[AirPlay-Server] Preserving user config: " + configPath);
                }
                catch
                {
                    // 读取失败则不保留
                }
            }

            // 如果已存在，先删除（确保干净环境）
            if (Directory.Exists(appDir))
            {
                try
                {
                    Directory.Delete(appDir, true);
                }
                catch
                {
                    // 如果删除失败（文件被锁定），尝试继续
                }
            }
            Directory.CreateDirectory(appDir);

            // 提取 zip 数据并解压
            byte[] zipData = new byte[zipLength];
            Array.Copy(exeBytes, zipDataStart, zipData, 0, zipLength);

            using (var ms = new MemoryStream(zipData))
            using (var archive = new ZipArchive(ms, ZipArchiveMode.Read))
            {
                foreach (ZipArchiveEntry entry in archive.Entries)
                {
                    string fullPath = Path.Combine(appDir, entry.FullName);
                    string fullDir = Path.GetDirectoryName(fullPath);
                    if (!string.IsNullOrEmpty(fullDir))
                    {
                        Directory.CreateDirectory(fullDir);
                    }
                    if (!entry.FullName.EndsWith("/") && !entry.FullName.EndsWith("\\"))
                    {
                        entry.ExtractToFile(fullPath, true);
                    }
                }
            }

            // 恢复配置：优先使用 exe 旁边的配置，其次恢复上次保留的配置
            if (hasExternalConfig)
            {
                // 最高优先级：exe 旁边的 application.properties（用户最直观的编辑方式）
                try
                {
                    File.Copy(externalConfig, configPath, true);
                    Console.WriteLine("[AirPlay-Server] Using external config: " + externalConfig);
                }
                catch
                {
                    // 复制失败则回退到保留的配置或默认配置
                    if (savedConfig != null)
                    {
                        File.WriteAllBytes(configPath, savedConfig);
                        Console.WriteLine("[AirPlay-Server] User config restored (fallback).");
                    }
                }
            }
            else if (savedConfig != null)
            {
                // 次优先级：恢复上次保留的配置
                try
                {
                    File.WriteAllBytes(configPath, savedConfig);
                    Console.WriteLine("[AirPlay-Server] User config restored.");
                }
                catch
                {
                    // 恢复失败则使用默认配置
                }
            }

            // 5. 设置环境变量
            string gstreamerRoot = Path.Combine(appDir, "gstreamer") + "\\";
            string gstreamerBin = Path.Combine(gstreamerRoot, "bin");
            string gstPluginPath = Path.Combine(gstreamerRoot, @"lib\gstreamer-1.0");
            string binDir = Path.Combine(appDir, "bin");
            string ffmpegPath = Path.Combine(binDir, "ffmpeg.exe");
            string jarPath = Path.Combine(appDir, "java-airplay-server-1.0.8.jar");
            string jreBin = Path.Combine(appDir, @"jre\bin");

            SetEnvironmentVariable("GSTREAMER_1_0_ROOT_MSVC_X86_64", gstreamerRoot);
            SetEnvironmentVariable("GST_PLUGIN_PATH", gstPluginPath);
            SetEnvironmentVariable("FFMPEG_PATH", ffmpegPath);

            // PATH 前置 gstreamer\bin, bin, jre\bin
            string currentPath = Environment.GetEnvironmentVariable("PATH") ?? "";
            string newPath = gstreamerBin + ";" + binDir + ";" + jreBin + ";" + currentPath;
            SetEnvironmentVariable("PATH", newPath);

            // 6. 启动 java.exe（使用 java 而非 javaw，显示控制台窗口输出日志）
            string javaExe = Path.Combine(jreBin, "java.exe");
            if (!File.Exists(javaExe))
            {
                MessageBox.Show("java.exe not found at: " + javaExe,
                    "AirPlay-Server Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
            if (!File.Exists(jarPath))
            {
                // 尝试查找其他版本的 jar
                string[] jars = Directory.GetFiles(appDir, "java-airplay-server-*.jar");
                if (jars.Length > 0)
                {
                    jarPath = jars[0];
                }
                else
                {
                    MessageBox.Show("JAR file not found at: " + jarPath,
                        "AirPlay-Server Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return 1;
                }
            }

            Console.WriteLine("[AirPlay-Server] Starting Java process...");
            Console.WriteLine("[AirPlay-Server] JAR: " + jarPath);
            Console.WriteLine("[AirPlay-Server] Config: " + configPath);
            Console.WriteLine("[AirPlay-Server] Working dir: " + appDir);
            Console.WriteLine("[AirPlay-Server] --- Java logs below ---");

            var psi = new ProcessStartInfo
            {
                FileName = javaExe,
                Arguments = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar \"" + jarPath + "\" --spring.config.location=\"" + configPath + "\"",
                UseShellExecute = false,
                CreateNoWindow = false,  // 显示控制台窗口，让 Spring Boot 日志输出到控制台
                WorkingDirectory = appDir
            };

            // 传递环境变量给子进程
            foreach (string envVar in new[] { "GSTREAMER_1_0_ROOT_MSVC_X86_64", "GST_PLUGIN_PATH", "FFMPEG_PATH", "PATH" })
            {
                psi.EnvironmentVariables[envVar] = Environment.GetEnvironmentVariable(envVar);
            }

            Process javaProc = Process.Start(psi);
            if (javaProc == null)
            {
                MessageBox.Show("Failed to start Java process.",
                    "AirPlay-Server Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }

            // 7. 等待 Java 进程退出（stub 作为父进程保持运行，控制台保持打开）
            javaProc.WaitForExit();

            // 8. 清理临时目录
            try
            {
                Directory.Delete(appDir, true);
            }
            catch
            {
                // 清理失败不影响退出
            }

            return 0;
        }
        catch (Exception ex)
        {
            MessageBox.Show("SFX extraction failed: " + ex.Message + "\n\n" + ex.StackTrace,
                "AirPlay-Server Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }
}
