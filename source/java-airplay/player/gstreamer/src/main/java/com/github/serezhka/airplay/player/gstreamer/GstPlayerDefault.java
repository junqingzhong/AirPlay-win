package com.github.serezhka.airplay.player.gstreamer;

import com.github.serezhka.airplay.server.AirPlayConfig;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;

public class GstPlayerDefault extends GstPlayer {

    public GstPlayerDefault() {
        super();
    }

    public GstPlayerDefault(AirPlayConfig config) {
        super(config);
    }

    @Override
    protected Pipeline createH264Pipeline() {
        String pipeline = (config != null && config.isLandscape())
                ? "appsrc name=h264-src ! h264parse ! avdec_h264 ! videoflip method=clockwise ! autovideosink sync=false"
                : "appsrc name=h264-src ! h264parse ! avdec_h264 ! autovideosink sync=false";
        return (Pipeline) Gst.parseLaunch(pipeline);
    }
}
