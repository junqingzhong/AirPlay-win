package com.github.serezhka.airplay.player.ffmpeg;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

@Slf4j
public class FFmpegPlayer implements AirPlayConsumer {

    private final AirPlayConfig config;
    private Process h264Process;

    // Audio pipeline: ffmpeg (LATM decode) -> Java Sound API (PCM playback)
    private Process audioDecodeProcess;
    private OutputStream audioDecodeStdin;
    private SourceDataLine audioLine;
    private AudioStreamInfo.CompressionType audioCompressionType;
    private boolean audioPipelineFailed = false;

    // 音量控制（对应 AirPlay RTSP SET_PARAMETER volume 参数）
    private volatile float pendingVolumeDb = 0.0f;
    private FloatControl volumeControl;

    public FFmpegPlayer() {
        this(null);
    }

    public FFmpegPlayer(AirPlayConfig config) {
        this.config = config;
    }

    /**
     * 查找可执行文件路径。搜索顺序：
     * 1. 环境变量 FFMPEG_PATH 所在目录
     * 2. 当前工作目录 ./bin/
     * 3. 系统 PATH
     * 确保在 SFX 解压环境（%TEMP%\AirPlay-Server\）中也能找到 ffmpeg.exe/ffplay.exe。
     */
    private static String findExecutable(String name) {
        // 1. 检查 FFMPEG_PATH 环境变量
        String ffmpegPath = System.getenv("FFMPEG_PATH");
        if (ffmpegPath != null && !ffmpegPath.isEmpty()) {
            File dir = new File(ffmpegPath).getParentFile();
            if (dir != null) {
                File exe = new File(dir, name + ".exe");
                if (exe.exists()) return exe.getAbsolutePath();
            }
        }
        // 2. 检查 ./bin/ 目录（SFX 解压后的标准布局）
        File binDir = new File("bin");
        if (!binDir.isAbsolute()) {
            binDir = new File(System.getProperty("user.dir"), "bin");
        }
        File exe = new File(binDir, name + ".exe");
        if (exe.exists()) return exe.getAbsolutePath();
        // 3. 回退到 PATH 查找（名称不含 .exe，ProcessBuilder 会自动查找）
        return name;
    }

    /**
     * 获取 MASTER_GAIN 控制器并应用待设置的音量。
     * 在 audioLine.open() 和 audioLine.start() 之后调用。
     */
    private void initVolumeControl() {
        try {
            volumeControl = (FloatControl) audioLine.getControl(FloatControl.Type.MASTER_GAIN);
            volumeControl.setValue(mapAirPlayVolume(pendingVolumeDb));
            log.info("Volume control enabled: {} dB (mapped: {} dB)", pendingVolumeDb, mapAirPlayVolume(pendingVolumeDb));
        } catch (IllegalArgumentException e) {
            log.warn("MASTER_GAIN control not available, volume control disabled");
            volumeControl = null;
        }
    }

    /**
     * 将 AirPlay 音量值（-144 到 0 dB）映射到 Java Sound MASTER_GAIN 范围。
     */
    private float mapAirPlayVolume(float airPlayDb) {
        if (airPlayDb <= -80.0f) {
            return -80.0f;
        }
        return airPlayDb;
    }

    @Override
    public void onVolumeChange(float volumeDb) {
        log.info("Applying AirPlay volume: {} dB", volumeDb);
        pendingVolumeDb = volumeDb;
        if (volumeControl != null) {
            try {
                volumeControl.setValue(mapAirPlayVolume(volumeDb));
            } catch (Exception e) {
                log.warn("Failed to set MASTER_GAIN: {}", e.getMessage());
            }
        }
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        try {
            String videoFilter = (config != null && config.isLandscape())
                    ? "setpts=0,transpose=1"
                    : "setpts=0";
            ProcessBuilder pb = new ProcessBuilder(List.of(
                    findExecutable("ffplay"), "-fs",
                    "-f", "h264",
                    "-codec:v", "h264",
                    "-probesize", "32",
                    "-analyzeduration", "0",
                    "-vf", videoFilter,
                    "-flags", "low_delay",
                    "-"));
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            h264Process = pb.start();
        } catch (IOException e) {
            log.error("Failed to start ffplay for video. Video will not be displayed. "
                    + "Install ffplay or use player.implementation=gstreamer for video. Error: {}", e.getMessage());
            // 不抛异常，音频仍可工作
        }
    }

    @Override
    public void onVideo(byte[] bytes) {
        try {
            if (h264Process != null && h264Process.isAlive()) {
                h264Process.getOutputStream().write(bytes);
                h264Process.getOutputStream().flush();
            }
        } catch (IOException e) {
            log.error("onVideo write failed: {}", e.getMessage());
        }
    }

    @Override
    public void onVideoSrcDisconnect() {
        if (h264Process != null) {
            h264Process.destroy();
            h264Process = null;
        }
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        this.audioCompressionType = audioStreamInfo.getCompressionType();
        if (audioCompressionType == AudioStreamInfo.CompressionType.AAC_ELD
                || audioCompressionType == AudioStreamInfo.CompressionType.AAC) {
            startAacEldPipeline();
        } else if (audioCompressionType == AudioStreamInfo.CompressionType.ALAC) {
            startAlacPipeline();
        } else {
            log.warn("Unsupported audio compression type: {}. Audio disabled.", audioCompressionType);
            audioPipelineFailed = true;
        }
    }

    /**
     * Spawn audio pipeline:
     *   ffmpeg -f latm -codec:a aac -i pipe:0 -f s16le -ar 44100 -ac 2 pipe:1
     *   | Java Sound API (SourceDataLine) PCM playback
     *
     * The Java side wraps each raw AAC-ELD frame into a LOAS frame before
     * writing to ffmpeg stdin (see wrapLatm()).
     */
    private void startAacEldPipeline() {
        try {
            ProcessBuilder dec = new ProcessBuilder(List.of(
                    findExecutable("ffmpeg"), "-hide_banner", "-loglevel", "error",
                    "-f", "latm",
                    "-codec:a", "aac",
                    "-probesize", "32",
                    "-analyzeduration", "0",
                    "-i", "pipe:0",
                    "-f", "s16le",
                    "-ar", "44100",
                    "-ac", "2",
                    "pipe:1"));
            dec.redirectError(ProcessBuilder.Redirect.INHERIT);
            audioDecodeProcess = dec.start();
            audioDecodeStdin = audioDecodeProcess.getOutputStream();

            // Java Sound API 播放 PCM（替代 ffplay）
            AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            audioLine = (SourceDataLine) AudioSystem.getLine(info);
            audioLine.open(format, 16384);
            audioLine.start();
            initVolumeControl(); // 获取音量控制器

            // 桥接线程：ffmpeg stdout -> SourceDataLine
            Thread bridge = new Thread(() -> {
                try (InputStream in = audioDecodeProcess.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        audioLine.write(buf, 0, n);
                    }
                } catch (IOException e) {
                    log.debug("audio bridge ended: {}", e.getMessage());
                }
            }, "ffmpeg-audio-bridge");
            bridge.setDaemon(true);
            bridge.start();

            log.info("AAC-ELD audio pipeline started (ffmpeg LATM decoder + Java Sound API playback)");
        } catch (IOException | LineUnavailableException e) {
            log.error("Failed to start AAC-ELD audio pipeline: {}", e.getMessage(), e);
            audioPipelineFailed = true;
        }
    }

    /**
     * ALAC 解码管线：
     *   ffmpeg -codec:a alac -i pipe:0 -f s16le -ar 44100 -ac 2 pipe:1
     *   | Java Sound API (SourceDataLine) PCM playback
     *
     * 与 AAC-ELD 不同，ALAC 原始帧可直接被 ffmpeg 解码，无需 LATM 封装。
     */
    private void startAlacPipeline() {
        try {
            ProcessBuilder dec = new ProcessBuilder(List.of(
                    findExecutable("ffmpeg"), "-hide_banner", "-loglevel", "error",
                    "-codec:a", "alac",
                    "-probesize", "32", "-analyzeduration", "0",
                    "-i", "pipe:0",
                    "-f", "s16le", "-ar", "44100", "-ac", "2",
                    "pipe:1"));
            dec.redirectError(ProcessBuilder.Redirect.INHERIT);
            audioDecodeProcess = dec.start();
            audioDecodeStdin = audioDecodeProcess.getOutputStream();

            AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            audioLine = (SourceDataLine) AudioSystem.getLine(info);
            audioLine.open(format, 16384);
            audioLine.start();
            initVolumeControl(); // 获取音量控制器

            Thread bridge = new Thread(() -> {
                try (InputStream in = audioDecodeProcess.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        audioLine.write(buf, 0, n);
                    }
                } catch (IOException e) {
                    log.debug("audio bridge ended: {}", e.getMessage());
                }
            }, "ffmpeg-alac-bridge");
            bridge.setDaemon(true);
            bridge.start();

            log.info("ALAC audio pipeline started (ffmpeg alac decoder + Java Sound API playback)");
        } catch (IOException | LineUnavailableException e) {
            log.error("Failed to start ALAC audio pipeline: {}", e.getMessage(), e);
            audioPipelineFailed = true;
        }
    }

    @Override
    public void onAudio(byte[] bytes) {
        if (audioPipelineFailed || audioDecodeStdin == null) {
            return;
        }
        try {
            if (audioCompressionType == AudioStreamInfo.CompressionType.AAC_ELD
                    || audioCompressionType == AudioStreamInfo.CompressionType.AAC) {
                byte[] latm = wrapLatm(bytes);
                audioDecodeStdin.write(latm);
                audioDecodeStdin.flush();
            } else if (audioCompressionType == AudioStreamInfo.CompressionType.ALAC) {
                audioDecodeStdin.write(bytes);
                audioDecodeStdin.flush();
            }
        } catch (IOException e) {
            log.error("onAudio write failed: {}", e.getMessage());
            audioPipelineFailed = true;
        }
    }

    @Override
    public void onAudioSrcDisconnect() {
        if (audioDecodeProcess != null) {
            audioDecodeProcess.destroy();
            audioDecodeProcess = null;
        }
        volumeControl = null; // 释放引用，下次启动音频时重新获取
        if (audioLine != null) {
            audioLine.drain();
            audioLine.close();
            audioLine = null;
        }
        audioDecodeStdin = null;
    }

    /**
     * Wrap a raw AAC-ELD frame into a LOAS (Low Overhead Audio Stream) frame.
     *
     * LOAS frame layout:
     *   [16 bit sync: 0x02B7]
     *   [13 bit audioMuxLengthBytes]
     *   [AudioMuxElement: audioMuxLengthBytes bytes]
     *
     * AudioMuxElement layout (ISO 14496-3):
     *   useSameMux (1 bit) = 0
     *   StreamMuxConfig (60 bit, fixed for AAC-ELD 44100 stereo, ASC=f8e85000)
     *   PayloadLengthInfo (12+ bit, frameLengthType=0)
     *   Payload (AAC-ELD frame bytes)
     *   Padding to byte boundary
     */
    private static byte[] wrapLatm(byte[] aacFrame) {
        BitWriter bw = new BitWriter();

        // AudioMuxElement
        bw.writeBit(0); // useSameMux = 0

        // StreamMuxConfig (fixed for AAC-ELD 44100Hz stereo, ASC = f8e85000)
        bw.writeBit(0); // audioMuxVersion = 0
        bw.writeBit(1); // allStreamsSameTimeFraming = 1
        bw.writeBits(0, 6); // numSubFrames = 0
        bw.writeBits(0, 4); // numProgram = 0
        bw.writeBits(0, 3); // numLayer = 0

        // AudioSpecificConfig (f8e85000, 32 bit)
        // AOT=39 (AAC-ELD), samplingFreqIndex=4 (44100), channelConfig=2
        bw.writeBits(0xF8L, 8);
        bw.writeBits(0xE8L, 8);
        bw.writeBits(0x50L, 8);
        bw.writeBits(0x00L, 8);

        bw.writeBits(0, 3); // frameLengthType = 0
        bw.writeBits(0xFFL, 8); // latmBufferFullness = 0xFF
        bw.writeBit(0); // otherDataPresent = 0
        bw.writeBit(0); // crcCheckPresent = 0

        // PayloadLengthInfo (frameLengthType = 0)
        // Each 255 bytes encoded as 0b1111 + 0xFF; remainder as 0b1111 + r
        int remaining = aacFrame.length;
        while (remaining >= 255) {
            bw.writeBits(0xF, 4); // 0b1111 prefix
            bw.writeBits(255, 8);
            remaining -= 255;
        }
        bw.writeBits(0xF, 4); // 0b1111 prefix
        bw.writeBits(remaining, 8);

        // Payload: raw AAC-ELD frame
        for (byte b : aacFrame) {
            bw.writeBits(b & 0xFF, 8);
        }

        // flush() pads to byte boundary
        byte[] audioMuxElement = bw.toByteArray();

        // Build LOAS frame
        int audioMuxLength = audioMuxElement.length;
        byte[] loas = new byte[4 + audioMuxLength];
        loas[0] = 0x02; // sync high byte
        loas[1] = (byte) 0xB7; // sync low byte
        loas[2] = (byte) ((audioMuxLength >> 8) & 0x1F); // length high (13-bit, top 3 bits unused)
        loas[3] = (byte) (audioMuxLength & 0xFF); // length low
        System.arraycopy(audioMuxElement, 0, loas, 4, audioMuxLength);

        return loas;
    }

    /**
     * Simple bit-level writer for building LATM/LOAS frames.
     */
    private static class BitWriter {
        private final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        private int currentByte = 0;
        private int bitCount = 0;

        void writeBit(int bit) {
            currentByte = (currentByte << 1) | (bit & 1);
            bitCount++;
            if (bitCount == 8) {
                bos.write(currentByte);
                currentByte = 0;
                bitCount = 0;
            }
        }

        void writeBits(int value, int numBits) {
            for (int i = numBits - 1; i >= 0; i--) {
                writeBit((value >> i) & 1);
            }
        }

        void writeBits(long value, int numBits) {
            for (int i = numBits - 1; i >= 0; i--) {
                writeBit((int) ((value >> i) & 1));
            }
        }

        void flush() {
            if (bitCount > 0) {
                currentByte <<= (8 - bitCount); // pad with zeros on the right
                bos.write(currentByte);
                currentByte = 0;
                bitCount = 0;
            }
        }

        byte[] toByteArray() {
            flush();
            return bos.toByteArray();
        }
    }
}
