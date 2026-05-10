/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import lombok.extern.slf4j.Slf4j;

/**
 * Composite device for managing an RGB LED triad.
 * <p>
 * This class coordinates three {@link PwmLed} instances to provide unified RGB control.
 * </p>
 */
@Slf4j
public final class MultiRgbLed implements AutoCloseable {

    private final PwmLed red;
    private final PwmLed green;
    private final PwmLed blue;

    /**
     * Constructs a composite RGB LED.
     *
     * @param red Red channel device.
     * @param green Green channel device.
     * @param blue Blue channel device.
     */
    public MultiRgbLed(final PwmLed red, final PwmLed green, final PwmLed blue) {
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    public void enable() {
        red.enable();
        green.enable();
        blue.enable();
    }

    public void disable() {
        red.disable();
        green.disable();
        blue.disable();
    }

    /**
     * Sets the RGB values.
     *
     * @param periodNs Common period for all channels.
     * @param rNs Red duty cycle.
     * @param gNs Green duty cycle.
     * @param bNs Blue duty cycle.
     */
    public void setRgb(final long periodNs, final long rNs, final long gNs, final long bNs) {
        red.setPulse(periodNs, rNs);
        green.setPulse(periodNs, gNs);
        blue.setPulse(periodNs, bNs);
    }

    public void off(final long periodNs) {
        setRgb(periodNs, 0, 0, 0);
    }

    @Override
    public void close() {
        log.debug("Closing MultiRgbLed composite");
        red.close();
        green.close();
        blue.close();
    }
}
