package com.github.serezhka.airplay.server;

import lombok.Data;

@Data
public class AirPlayConfig {
    private String serverName;
    private int width;
    private int height;
    private int fps;
    /**
     * Force landscape orientation. When true, width/height are auto-swapped
     * so that width >= height in the advertised display, and the player
     * applies a clockwise 90-degree rotation to the incoming video stream.
     */
    private boolean landscape;
}
