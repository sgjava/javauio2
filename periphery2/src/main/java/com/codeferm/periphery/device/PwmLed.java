/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import lombok.extern.slf4j.Slf4j;

/**
 * PWM-controlled LED implementation.
 * <p>
 * Provides high-level control over LED brightness using a {@link PwmDevice} transport. Supports enabling, disabling, and granular
 * brightness control via pulse width or percentage-based duty cycles.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class PwmLed implements AutoCloseable {

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
        log.atDebug().log("Initializing PwmLed with transport: {}",
                pwm.getClass().getSimpleName());
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
     * Sets the LED brightness by adjusting the PWM pulse directly.
     *
     * @param periodNs Total signal period in nanoseconds.
     * @param dutyCycleNs Pulse width in nanoseconds (brightness level).
     */
    public void setPulse(final long periodNs, final long dutyCycleNs) {
        pwm.setPulse(periodNs, dutyCycleNs);
    }

    /**
     * Sets the LED brightness using a percentage (0.0 to 1.0).
     *
     * @param periodNs Total signal period in nanoseconds.
     * @param percentage Brightness percentage where 1.0 is full brightness.
     */
    public void setBrightness(final long periodNs, final double percentage) {
        pwm.setDutyCycle(periodNs, percentage);
    }

    /**
     * Releases the underlying PWM resources.
     * <p>
     * Ensures the transport is closed. The transport implementation is responsible for silencing the output during
     * its own closure.
     * </p>
     */
    @Override
    public void close() {
        log.atDebug().log("Closing PwmLed");
        pwm.close();
    }
}
