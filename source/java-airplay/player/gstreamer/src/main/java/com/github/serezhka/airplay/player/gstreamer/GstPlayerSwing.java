package com.github.serezhka.airplay.player.gstreamer;

import com.formdev.flatlaf.FlatDarkLaf;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConfig;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.swing.GstVideoComponent;

import javax.swing.*;
import java.awt.*;

public class GstPlayerSwing extends GstPlayer {

    static {
        FlatDarkLaf.setup();
    }

    private final JFrame window;

    public GstPlayerSwing() {
        this(null);
    }

    public GstPlayerSwing(AirPlayConfig config) {
        super(config);
        AppSink sink = (AppSink) h264Pipeline.getElementByName("sink");
        GstVideoComponent vc = new GstVideoComponent(sink);

        window = new JFrame("AirPlay player");
        window.add(vc);
        // Landscape: prefer wider window
        if (config != null && config.isLandscape()) {
            vc.setPreferredSize(new Dimension(1024, 768));
        } else {
            vc.setPreferredSize(new Dimension(800, 600));
        }
        window.pack();
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    @Override
    protected Pipeline createH264Pipeline() {
        String pipeline = (config != null && config.isLandscape())
                ? "appsrc name=h264-src ! h264parse ! avdec_h264 ! videoflip method=clockwise ! videoconvert ! appsink name=sink sync=false"
                : "appsrc name=h264-src ! h264parse ! avdec_h264 ! videoconvert ! appsink name=sink sync=false";
        return (Pipeline) Gst.parseLaunch(pipeline);
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        window.setVisible(true);
        super.onVideoFormat(videoStreamInfo);
    }

    @Override
    public void onVideoSrcDisconnect() {
        window.setVisible(false);
        super.onVideoSrcDisconnect();
    }
}
