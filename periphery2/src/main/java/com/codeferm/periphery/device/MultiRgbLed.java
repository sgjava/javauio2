/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import lombok.extern.slf4j.Slf4j;

/**
 * Multi-channel RGB LED device using the PwmDevice interface.
 * <p>
 * This version supports dimming via PWM. Each channel can be a hardware or software PWM implementation.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class MultiRgbLed implements AutoCloseable {

    private final PwmDevice red;
    private final PwmDevice green;
    private final PwmDevice blue;

    /**
     * Constructor using generic PWM devices.
     *
     * @param red PWM device for red channel.
     * @param green PWM device for green channel.
     * @param blue PWM device for blue channel.
     */
    public MultiRgbLed(final PwmDevice red, final PwmDevice green, final PwmDevice blue) {
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    /**
     * Enables all PWM channels.
     */
    public void enable() {
        red.enable();
        green.enable();
        blue.enable();
    }

    /**
     * Disables all PWM channels.
     */
    public void disable() {
        red.disable();
        green.disable();
        blue.disable();
    }

    /**
     * Sets the RGB color using duty cycle ratios.
     *
     * @param periodNs Total period in nanoseconds.
     * @param rDcNs Red duty cycle in nanoseconds.
     * @param gDcNs Green duty cycle in nanoseconds.
     * @param bDcNs Blue duty cycle in nanoseconds.
     */
    public void setRgb(final long periodNs, final long rDcNs, final long gDcNs, final long bDcNs) {
        red.setPulse(periodNs, rDcNs);
        green.setPulse(periodNs, gDcNs);
        blue.setPulse(periodNs, bDcNs);
    }

    /**
     * Turns all channels off by setting duty cycle to 0.
     *
     * @param periodNs Current period to maintain.
     */
    public void off(final long periodNs) {
        setRgb(periodNs, 0, 0, 0);
    }

    @Override
    public void close() {
        log.debug("Closing MultiRgbLed");
        // Interface handles closing internal resources (FFM arena, threads, handles)
        red.close();
        green.close();
        blue.close();
    }
}
