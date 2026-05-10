/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import lombok.extern.slf4j.Slf4j;

/**
 * PWM-controlled LED implementation.
 * <p>
 * This class provides high-level control over LED brightness using a {@link PwmDevice} transport. It supports enabling, disabling,
 * and setting brightness via duty cycle adjustments.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class PwmLed implements AutoCloseable {

    /**
     * The underlying PWM transport (Hardware or Software).
     */
    private final PwmDevice pwm;

    /**
     * Constructs a PwmLed using a unified PWM transport.
     *
     * @param pwm The {@link PwmDevice} implementation to use.
     */
    public PwmLed(final PwmDevice pwm) {
        this.pwm = pwm;
    }

    /**
     * Enables the LED output.
     */
    public void enable() {
        pwm.enable();
    }

    /**
     * Disables the LED output.
     */
    public void disable() {
        pwm.disable();
    }

    /**
     * Sets the LED brightness by adjusting the PWM pulse.
     *
     * @param periodNs Total signal period in nanoseconds.
     * @param dutyCycleNs Pulse width in nanoseconds (brightness level).
     */
    public void setPulse(final long periodNs, final long dutyCycleNs) {
        pwm.setPulse(periodNs, dutyCycleNs);
    }

    /**
     * Releases the underlying PWM resources.
     */
    @Override
    public void close() {
        if (log.isDebugEnabled()) {
            log.debug("Closing PWM LED");
        }
        pwm.close();
    }
}
