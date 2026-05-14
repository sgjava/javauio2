/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

/**
 * Common interface for Pulse Width Modulation (PWM) devices.
 * <p>
 * Provides a hardware-agnostic contract for managing PWM signals, allowing applications to switch between kernel-level hardware PWM
 * and software-timed GPIO PWM without changing business logic.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
public interface PwmDevice extends AutoCloseable {

    /**
     * Enables the PWM output signal.
     */
    void enable();

    /**
     * Disables the PWM output signal.
     */
    void disable();

    /**
     * Sets the PWM pulse parameters in nanoseconds.
     *
     * @param periodNs Total period of the signal (e.g., 1,000,000 for 1kHz).
     * @param dutyCycleNs High-time of the signal. Must be <= periodNs.
     */
    void setPulse(final long periodNs, final long dutyCycleNs);

    /**
     * Utility method to set pulse based on duty cycle percentage.
     *
     * * @param periodNs Total period of the signal in nanoseconds.
     * @param percentage Duty cycle percentage (0.0 to 1.0).
     */
    default void setDutyCycle(final long periodNs, final double percentage) {
        final var validated = Math.clamp(percentage, 0.0, 1.0);
        setPulse(periodNs, (long) (periodNs * validated));
    }

    /**
     * Closes the device and releases all associated native resources.
     */
    @Override
    void close();
}
