/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

/**
 * Abstract touch controller base class.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
public abstract class AbstractTouch implements AutoCloseable {

    /**
     * Touch point coordinate representation.
     *
     * @param x X coordinate.
     * @param y Y coordinate.
     */
    public record TouchPoint(int x, int y) {

    }

    /**
     * Initialize touch controller and communication lines.
     */
    public abstract void open();

    /**
     * Check if a touch event is currently active.
     *
     * @return True if touched, false otherwise.
     */
    public abstract boolean isPressed();

    /**
     * Read raw or calibrated coordinates.
     *
     * @return TouchPoint containing X and Y coordinates.
     */
    public abstract TouchPoint readCoordinates();

    @Override
    public abstract void close();
}
