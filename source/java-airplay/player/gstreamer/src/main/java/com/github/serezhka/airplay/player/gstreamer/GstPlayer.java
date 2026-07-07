package com.github.serezhka.airplay.player.gstreamer;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.glib.GLib;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class GstPlayer implements AirPlayConsumer {

    static {
        GstPlayerUtils.configurePaths();
        GLib.setEnv("GST_DEBUG", "3", true);
        Gst.init(Version.of(1, 10), "BasicPipeline");
    }

    protected final AirPlayConfig config;
    protected final Pipeline h264Pipeline;
    // 音频管线改为懒创建，避免同时 play 两个管线导致冲突
    private Pipeline alacPipeline;
    private Pipeline aacEldPipeline;

    private final AppSrc h264Src;
    private AppSrc alacSrc;
    private AppSrc aacEldSrc;

    // Java Sound API 音频输出（替代 GStreamer autoaudiosink，更可靠）
    // GStreamer 管线解码后输出 PCM S16LE 到 appsink，Java 侧通过 SourceDataLine 播放
    private SourceDataLine audioLine;
    private volatile boolean audioRunning = false;

    // 音量控制（对应 AirPlay RTSP SET_PARAMETER volume 参数）
    // AirPlay volume 范围: -144.0（静音）到 0.0（最大），单位 dB
    // 映射到 Java Sound API FloatControl.Type.MASTER_GAIN
    private volatile float pendingVolumeDb = 0.0f; // 默认最大音量
    private FloatControl volumeControl;

    private Pipeline hlsPipeline;

    private AudioStreamInfo.CompressionType audioCompressionType;

    public GstPlayer() {
        this(null);
    }

    public GstPlayer(AirPlayConfig config) {
        this.config = config;
        h264Pipeline = createH264Pipeline();

        h264Src = (AppSrc) h264Pipeline.getElementByName("h264-src");
        h264Src.setStreamType(AppSrc.StreamType.STREAM);
        h264Src.setCaps(Caps.fromString("video/x-h264,colorimetry=bt709,stream-format=(string)byte-stream,alignment=(string)au"));
        h264Src.set("is-live", true);
        h264Src.set("format", Format.TIME);
        h264Src.set("emit-signals", true);
        // 音频管线在 onAudioFormat() 时根据实际 AudioStreamInfo 懒创建
    }

    protected abstract Pipeline createH264Pipeline();

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        h264Pipeline.play();
    }

    @Override
    public void onVideo(byte[] bytes) {
        Buffer buf = new Buffer(bytes.length);
        buf.map(true).put(bytes); // ByteBuffer.wrap(bytes)
        h264Src.pushBuffer(buf);
    }

    @Override
    public void onVideoSrcDisconnect() {
        h264Pipeline.stop();
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        this.audioCompressionType = audioStreamInfo.getCompressionType();
        log.info("Audio format: {}", audioStreamInfo);

        try {
            // 先初始化 Java Sound API 输出（固定 44100Hz/16-bit/stereo）
            initAudioOutput();

            switch (audioCompressionType) {
                case ALAC -> {
                    createAlacPipeline(audioStreamInfo);
                    alacPipeline.play();
                    log.info("ALAC audio pipeline started (GStreamer decode -> appsink -> Java Sound API)");
                }
                case AAC_ELD, AAC -> {
                    createAacEldPipeline(audioStreamInfo);
                    aacEldPipeline.play();
                    log.info("AAC-ELD audio pipeline started (GStreamer decode -> appsink -> Java Sound API)");
                }
                default -> log.warn("Unsupported audio compression: {}", audioCompressionType);
            }
        } catch (Exception e) {
            log.error("Failed to start audio pipeline for {}: {}", audioCompressionType, e.getMessage(), e);
        }
    }

    /**
     * 初始化 Java Sound API 音频输出（SourceDataLine）。
     * 固定输出格式：44100Hz, 16-bit, stereo, PCM S16LE。
     * GStreamer 管线会将音频重采样为此格式后通过 appsink 输出。
     */
    private void initAudioOutput() {
        if (audioLine != null && audioLine.isOpen()) {
            return; // 已初始化
        }
        try {
            AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            audioLine = (SourceDataLine) AudioSystem.getLine(info);
            audioLine.open(format, 16384); // 16KB buffer
            audioLine.start();
            audioRunning = true;

            // 获取音量控制器（MASTER_GAIN），用于 AirPlay 音量控制
            try {
                volumeControl = (FloatControl) audioLine.getControl(FloatControl.Type.MASTER_GAIN);
                float mapped = mapAirPlayVolume(pendingVolumeDb);
                volumeControl.setValue(mapped);
                log.info("Java Sound API audio output initialized: {}, volume: {} dB (mapped: {} dB)",
                        format, pendingVolumeDb, mapped);
            } catch (IllegalArgumentException e) {
                log.warn("MASTER_GAIN control not available on this audio line, volume control disabled");
                volumeControl = null;
            }
        } catch (LineUnavailableException e) {
            log.error("Failed to initialize Java Sound API audio output: {}", e.getMessage(), e);
            audioRunning = false;
        }
    }

    /**
     * 将 AirPlay 音量值（-144 到 0 dB）映射到 Java Sound MASTER_GAIN 范围。
     * AirPlay: -144 = 静音, 0 = 最大
     * MASTER_GAIN: 典型范围 -80 到 +6 dB
     * -144 或低于 -80 映射为 -80（接近静音），其他值直接使用。
     */
    private float mapAirPlayVolume(float airPlayDb) {
        if (airPlayDb <= -80.0f) {
            return -80.0f; // MASTER_GAIN 最低值（接近静音）
        }
        return airPlayDb; // 0 dB = 最大，负值 = 衰减
    }

    @Override
    public void onVolumeChange(float volumeDb) {
        log.info("Applying AirPlay volume: {} dB", volumeDb);
        pendingVolumeDb = volumeDb;
        if (volumeControl != null) {
            try {
                float mapped = mapAirPlayVolume(volumeDb);
                volumeControl.setValue(mapped);
                log.debug("MASTER_GAIN set to {} dB", mapped);
            } catch (Exception e) {
                log.warn("Failed to set MASTER_GAIN: {}", e.getMessage());
            }
        }
    }

    /**
     * 懒创建 ALAC 音频管线。
     * 使用 appsink 替代 autoaudiosink：GStreamer 解码 ALAC → 重采样为 PCM S16LE → appsink → Java Sound API 播放。
     * 完全绕过 GStreamer 音频 sink 插件依赖（不依赖 gstdirectsound.dll/gstwasapi.dll）。
     * caps 根据实际 AudioStreamInfo 动态生成，ALAC magic cookie 动态填充采样率/位深/声道。
     */
    private void createAlacPipeline(AudioStreamInfo info) {
        int rate = getSampleRate(info);
        int channels = getChannels(info);
        int bitDepth = getBitDepth(info);
        String codecData = getAlacCodecData(rate, channels, bitDepth);
        String caps = String.format(
                "audio/x-alac,stream-format=raw,channels=(int)%d,rate=(int)%d,codec_data=(buffer)%s",
                channels, rate, codecData);

        alacPipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=alac-src ! avdec_alac ! audioconvert ! audioresample "
                + "! audio/x-raw,format=S16LE,rate=44100,channels=2 "
                + "! appsink name=alac-sink sync=false emit-signals=true max-buffers=50 drop=true");
        alacSrc = (AppSrc) alacPipeline.getElementByName("alac-src");
        alacSrc.setStreamType(AppSrc.StreamType.STREAM);
        alacSrc.setCaps(Caps.fromString(caps));
        alacSrc.set("is-live", true);
        alacSrc.set("format", Format.TIME);
        alacSrc.set("emit-signals", true);

        AppSink sink = (AppSink) alacPipeline.getElementByName("alac-sink");
        sink.set("emit-signals", true);
        sink.connect((AppSink.NEW_SAMPLE) elem -> {
            Sample sample = elem.pullSample();
            try {
                ByteBuffer buffer = sample.getBuffer().map(false);
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                if (audioRunning && audioLine != null && audioLine.isOpen()) {
                    audioLine.write(bytes, 0, bytes.length);
                }
            } finally {
                sample.disown();
            }
            return FlowReturn.OK;
        });
    }

    /**
     * 懒创建 AAC-ELD/AAC-LC 音频管线。
     * 同样使用 appsink + Java Sound API 输出。
     */
    private void createAacEldPipeline(AudioStreamInfo info) {
        int rate = getSampleRate(info);
        int channels = getChannels(info);
        String codecData = getAacEldCodecData(rate, channels);
        String caps = String.format(
                "audio/mpeg,mpegversion=(int)4,channels=(int)%d,rate=(int)%d,stream-format=raw,codec_data=(buffer)%s",
                channels, rate, codecData);

        aacEldPipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=aac-eld-src ! avdec_aac ! audioconvert ! audioresample "
                + "! audio/x-raw,format=S16LE,rate=44100,channels=2 "
                + "! appsink name=aac-eld-sink sync=false emit-signals=true max-buffers=50 drop=true");
        aacEldSrc = (AppSrc) aacEldPipeline.getElementByName("aac-eld-src");
        aacEldSrc.setStreamType(AppSrc.StreamType.STREAM);
        aacEldSrc.setCaps(Caps.fromString(caps));
        aacEldSrc.set("is-live", true);
        aacEldSrc.set("format", Format.TIME);
        aacEldSrc.set("emit-signals", true);

        AppSink sink = (AppSink) aacEldPipeline.getElementByName("aac-eld-sink");
        sink.set("emit-signals", true);
        sink.connect((AppSink.NEW_SAMPLE) elem -> {
            Sample sample = elem.pullSample();
            try {
                ByteBuffer buffer = sample.getBuffer().map(false);
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                if (audioRunning && audioLine != null && audioLine.isOpen()) {
                    audioLine.write(bytes, 0, bytes.length);
                }
            } finally {
                sample.disown();
            }
            return FlowReturn.OK;
        });
    }

    /**
     * 从 AudioStreamInfo.AudioFormat 名称解析采样率。
     * 格式名如 ALAC_44100_16_2, AAC_ELD_48000_2 等。
     */
    private int getSampleRate(AudioStreamInfo info) {
        if (info.getAudioFormat() == null) return 44100;
        String name = info.getAudioFormat().name();
        if (name.contains("48000")) return 48000;
        if (name.contains("44100")) return 44100;
        if (name.contains("24000")) return 24000;
        if (name.contains("16000")) return 16000;
        return 44100;
    }

    /**
     * 从 AudioStreamInfo.AudioFormat 名称解析声道数。
     * 格式名末尾 _1 = 单声道，_2 = 立体声。
     */
    private int getChannels(AudioStreamInfo info) {
        if (info.getAudioFormat() == null) return 2;
        return info.getAudioFormat().name().endsWith("_1") ? 1 : 2;
    }

    /**
     * 从 AudioStreamInfo.AudioFormat 名称解析位深（16 或 24）。
     * 仅 ALAC 格式有位深信息（ALAC_44100_16_2, ALAC_44100_24_2）。
     */
    private int getBitDepth(AudioStreamInfo info) {
        if (info.getAudioFormat() == null) return 16;
        String name = info.getAudioFormat().name();
        if (name.contains("_24_")) return 24;
        return 16;
    }

    /**
     * 动态生成 ALAC magic cookie（36 字节）。
     *
     * 结构：
     *   atomSize(4) + 'alac'(4) + version(4) + frameLength(4) +
     *   compatibleVersion(1) + bitDepth(1) + pb(1) + mb(1) + kb(1) + numChannels(1) +
     *   maxRun(2) + maxFrameBytes(4) + avgBitRate(4) + sampleRate(4)
     *
     * @param rate      采样率（44100, 48000 等）
     * @param channels  声道数（1 或 2）
     * @param bitDepth  位深（16 或 24）
     * @return 36 字节 magic cookie 的十六进制字符串
     */
    private String getAlacCodecData(int rate, int channels, int bitDepth) {
        int frameLength = 352; // ALAC 标准帧长
        // 00000024 = atom size (36)
        // 616c6163 = 'alac'
        // 00000000 = version 0
        // 00000160 = frameLength (352 = 0x160)
        // 00       = compatibleVersion 0
        // 10/18    = bitDepth (16=0x10, 24=0x18)
        // 28       = pb (40)
        // 0a       = mb (10)
        // 0e       = kb (14)
        // 01/02    = numChannels
        // 00ff     = maxRun (255)
        // 00000000 = maxFrameBytes
        // 00000000 = avgBitRate
        // 0000ac44 / 0000bb80 = sampleRate (44100 / 48000)
        return String.format(
                "00000024616c616300000000%08x00%02x280a0e%02x00ff00000000000000000000%08x",
                frameLength, bitDepth, channels, rate);
    }

    /**
     * AAC-ELD AudioSpecificConfig（4 字节）。
     * AOT=39 (AAC-ELD), freqIndex, channelConfig, frameLengthFlag=1 (480 samples)。
     */
    private String getAacEldCodecData(int rate, int channels) {
        if (rate == 48000 && channels == 2) return "f8e32000";
        if (rate == 48000 && channels == 1) return "f8e31000";
        if (rate == 44100 && channels == 1) return "f8e84000";
        return "f8e85000"; // 44100 stereo default
    }

    @Override
    public void onAudio(byte[] bytes) {
        Buffer buf = new Buffer(bytes.length);
        buf.map(true).put(bytes);
        try {
            switch (audioCompressionType) {
                case ALAC -> { if (alacSrc != null) alacSrc.pushBuffer(buf); }
                case AAC_ELD, AAC -> { if (aacEldSrc != null) aacEldSrc.pushBuffer(buf); }
            }
        } catch (Exception e) {
            log.debug("pushBuffer failed: {}", e.getMessage());
        }
    }

    @Override
    public void onAudioSrcDisconnect() {
        audioRunning = false;
        if (alacPipeline != null) {
            alacPipeline.stop();
            alacPipeline = null;
            alacSrc = null;
        }
        if (aacEldPipeline != null) {
            aacEldPipeline.stop();
            aacEldPipeline = null;
            aacEldSrc = null;
        }
        volumeControl = null; // 释放引用，下次 initAudioOutput 时重新获取
        if (audioLine != null) {
            try {
                audioLine.drain();
                audioLine.close();
            } catch (Exception e) {
                log.debug("audioLine close: {}", e.getMessage());
            }
            audioLine = null;
        }
    }

    @Override
    public void onMediaPlaylist(String playlistUri) {
        hlsPipeline = (Pipeline) Gst.parseLaunch("playbin3 uri=" + playlistUri);
        hlsPipeline.play();
    }

    @Override
    public void onMediaPlaylistRemove() {
        if (hlsPipeline != null) {
            hlsPipeline.stop();
        }
    }

    @Override
    public void onMediaPlaylistPause() {
        if (hlsPipeline != null && hlsPipeline.isPlaying()) {
            hlsPipeline.pause();
        }
    }

    @Override
    public void onMediaPlaylistResume() {
        if (hlsPipeline != null && !hlsPipeline.isPlaying()) {
            hlsPipeline.play();
        }
    }

    @Override
    public PlaybackInfo playbackInfo() {
        if (hlsPipeline != null) {
            return new PlaybackInfo(
                    hlsPipeline.queryDuration(TimeUnit.SECONDS),
                    hlsPipeline.queryPosition(TimeUnit.SECONDS));
        }
        return AirPlayConsumer.super.playbackInfo();
    }
}
