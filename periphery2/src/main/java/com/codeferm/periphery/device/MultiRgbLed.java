/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import lombok.extern.slf4j.Slf4j;

/**
 * Composite device for managing an RGB LED triad.
 * <p>
 * Coordinates three {@link PwmLed} instances to provide unified RGB control via PWM. This class follows the Composite pattern and
 * does not manage native memory directly.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
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

    /**
     * Enables all three PWM channels.
     */
    public void enable() {
        red.enable();
        green.enable();
        blue.enable();
    }

    /**
     * Disables all three PWM channels.
     */
    public void disable() {
        red.disable();
        green.disable();
        blue.disable();
    }

    /**
     * Sets the RGB values by adjusting duty cycles.
     *
     * @param periodNs Common period for all channels (nanoseconds).
     * @param rNs Red duty cycle (nanoseconds).
     * @param gNs Green duty cycle (nanoseconds).
     * @param bNs Blue duty cycle (nanoseconds).
     */
    public void setRgb(final long periodNs, final long rNs, final long gNs, final long bNs) {
        red.setPulse(periodNs, rNs);
        green.setPulse(periodNs, gNs);
        blue.setPulse(periodNs, bNs);
    }

    /**
     * Sets all channels to 0 duty cycle.
     *
     * * @param periodNs The PWM period to maintain.
     */
    public void off(final long periodNs) {
        setRgb(periodNs, 0, 0, 0);
    }

    /**
     * Releases all associated PWM resources.
     * <p>
     * Uses a suppressed exception pattern or individual try-catch to ensure all channels attempt closure even if one
     * fails.
     * </p>
     */
    @Override
    public void close() {
        log.debug("Closing MultiRgbLed composite");
        // We use a simple sequential close here. 
        // Since PwmLed.close() is idempotent and handles its own arena, 
        // we just ensure we call all three.
        try {
            red.close();
        } catch (final Exception e) {
            log.error("Error closing Red channel", e);
        }
        try {
            green.close();
        } catch (final Exception e) {
            log.error("Error closing Green channel", e);
        }
        try {
            blue.close();
        } catch (final Exception e) {
            log.error("Error closing Blue channel", e);
        }
    }
}
