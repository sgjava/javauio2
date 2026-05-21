/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import lombok.extern.slf4j.Slf4j;

/**
 * Composite device for managing an RGB LED triad.
 * <p>
 * Coordinates three {@link PwmDevice} instances to provide unified RGB control via PWM. This class follows the Composite pattern
 * and does not manage native memory directly.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class MultiRgbLed implements AutoCloseable {

    /**
     * Pulse Width Modulation device handle for the Red color segment.
     */
    private final PwmDevice red;

    /**
     * Pulse Width Modulation device handle for the Green color segment.
     */
    private final PwmDevice green;

    /**
     * Pulse Width Modulation device handle for the Blue color segment.
     */
    private final PwmDevice blue;

    /**
     * Constructs a composite RGB LED using polymorphic PWM bindings.
     *
     * @param red Red channel device interface.
     * @param green Green channel device interface.
     * @param blue Blue channel device interface.
     */
    public MultiRgbLed(final PwmDevice red, final PwmDevice green, final PwmDevice blue) {
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    /**
     * Enables all three PWM channels.
     */
    public void enable() {
        this.red.enable();
        this.green.enable();
        this.blue.enable();
    }

    /**
     * Disables all three PWM channels.
     */
    public void disable() {
        this.red.disable();
        this.green.disable();
        this.blue.disable();
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
        this.red.setPulse(periodNs, rNs);
        this.green.setPulse(periodNs, gNs);
        this.blue.setPulse(periodNs, bNs);
    }

    /**
     * Sets all channels to 0 duty cycle.
     *
     * @param periodNs The PWM period to maintain.
     */
    public void off(final long periodNs) {
        setRgb(periodNs, 0L, 0L, 0L);
    }

    /**
     * Releases all associated PWM resources.
     * <p>
     * Uses an isolated execution design to ensure all channels attempt closure even if an upstream channel fails.
     * </p>
     */
    @Override
    public void close() {
        log.debug("Closing MultiRgbLed composite");

        if (null != this.red) {
            try {
                this.red.close();
            } catch (final Exception e) {
                log.error("Error closing Red channel", e);
            }
        }
        if (null != this.green) {
            try {
                this.green.close();
            } catch (final Exception e) {
                log.error("Error closing Green channel", e);
            }
        }
        if (null != this.blue) {
            try {
                this.blue.close();
            } catch (final Exception e) {
                log.error("Error closing Blue channel", e);
            }
        }
    }
}
