package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

public interface AirPlayConsumer {

    void onVideoFormat(VideoStreamInfo videoStreamInfo);

    void onVideo(byte[] bytes);

    void onVideoSrcDisconnect();

    void onAudioFormat(AudioStreamInfo audioStreamInfo);

    void onAudio(byte[] bytes);

    void onAudioSrcDisconnect();

    /**
     * 音量变化回调（iPhone 侧音量调节时触发）。
     *
     * @param volumeDb 音量值（dB），范围 -144.0（静音）到 0.0（最大）。
     *                 典型值：0 = 最大，-30 = 中等，-144 = 静音。
     *                 对应 AirPlay RTSP SET_PARAMETER 中的 volume 参数。
     */
    default void onVolumeChange(float volumeDb) {
    }

    // HLS stuff, youtube
    default void onMediaPlaylist(String playlistUri) {
    }

    default void onMediaPlaylistRemove() {
    }

    default void onMediaPlaylistPause() {
    }

    default void onMediaPlaylistResume() {
    }

    default PlaybackInfo playbackInfo() {
        return new PlaybackInfo(0, 0);
    }

    record PlaybackInfo(double duration, double position) {
    }
}
