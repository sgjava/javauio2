/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import lombok.extern.slf4j.Slf4j;

/**
 * PWM-controlled LCD Backlight implementation.
 * <p>
 * Provides high-level control over LCD backlight brightness using a {@link PwmDevice} transport. Supports enabling, disabling, and
 * granular brightness control via pulse width or percentage-based duty cycles, with optional inversion for active-low hardware.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class PwmBacklight implements AutoCloseable {

    /**
     * The underlying PWM transport (Hardware Sysfs or Software GPIO).
     */
    private final PwmDevice pwm;

    /**
     * Whether the backlight logic is inverted (active-low).
     */
    private final boolean inverted;

    /**
     * Cached period in nanoseconds, required to calculate safe off-states.
     */
    private long periodNs;

    /**
     * Constructs a PwmBacklight using a unified PWM transport with inversion disabled by default.
     *
     * @param pwm The {@link PwmDevice} implementation to use.
     */
    public PwmBacklight(final PwmDevice pwm) {
        this(pwm, false);
    }

    /**
     * Constructs a PwmBacklight using a unified PWM transport.
     *
     * @param pwm The {@link PwmDevice} implementation to use.
     * @param inverted True if the hardware circuit is active-low (inverted).
     */
    public PwmBacklight(final PwmDevice pwm, final boolean inverted) {
        log.atDebug().log("Initializing PwmBacklight with transport: {}, inverted: {}",
                pwm.getClass().getSimpleName(), inverted);
        this.pwm = pwm;
        this.inverted = inverted;
    }

    /**
     * Enables the LCD backlight output.
     */
    public void enable() {
        log.atDebug().log("Enabling PwmBacklight output");
        pwm.enable();
    }

    /**
     * Disables the LCD backlight output after safely setting it to the off-state.
     */
    public void disable() {
        log.atDebug().log("Disabling PwmBacklight output and forcing safe off-state");
        if (periodNs > 0) {
            // For active-low (inverted), 100% duty cycle turns it off. For normal, 0% turns it off.
            final var offDutyCycle = inverted ? periodNs : 0L;
            pwm.setPulse(periodNs, offDutyCycle);
        }
        pwm.disable();
    }

    /**
     * Sets the LCD backlight brightness by adjusting the PWM pulse directly.
     *
     * @param periodNs Total signal period in nanoseconds.
     * @param dutyCycleNs Pulse width in nanoseconds (brightness level).
     */
    public void setPulse(final long periodNs, final long dutyCycleNs) {
        this.periodNs = periodNs;
        final var actualDutyCycle = inverted ? (periodNs - dutyCycleNs) : dutyCycleNs;
        pwm.setPulse(periodNs, actualDutyCycle);
    }

    /**
     * Sets the LCD backlight brightness using a percentage (0.0 to 1.0).
     *
     * @param periodNs Total signal period in nanoseconds.
     * @param percentage Brightness percentage where 1.0 is full brightness.
     */
    public void setBrightness(final long periodNs, final double percentage) {
        this.periodNs = periodNs;
        final var clamped = Math.max(0.0, Math.min(1.0, percentage));
        final var actualPercentage = inverted ? (1.0 - clamped) : clamped;
        pwm.setDutyCycle(periodNs, actualPercentage);
    }

    /**
     * Releases the underlying PWM resources safely by turning off the backlight and closing the transport.
     */
    @Override
    public void close() {
        log.atDebug().log("Closing PwmBacklight and releasing resources");
        try (pwm) {
            disable();
        } catch (final Exception e) {
            log.warn("Error disabling PWM during close: {}", e.getMessage());
        }
    }
}
