/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import lombok.extern.slf4j.Slf4j;

/**
 * Passive Speaker implementation supporting both hardware and software PWM.
 * <p>
 * This class provides high-level control over a speaker device via a {@link PwmDevice} transport. It ensures lifecycle safety by
 * mutes and disabling the underlying transport upon closure.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class PassiveSpeaker implements AutoCloseable {

    /**
     * The underlying PWM transport (Hardware or Software).
     */
    private final PwmDevice pwm;

    /**
     * Constructs a PassiveSpeaker using a unified PWM transport.
     *
     * @param pwm The {@link PwmDevice} implementation provided by the factory.
     */
    public PassiveSpeaker(final PwmDevice pwm) {
        if (log.isDebugEnabled()) {
            log.debug("Initializing PassiveSpeaker with transport: {}", pwm.getClass().getSimpleName());
        }
        this.pwm = pwm;
    }

    /**
     * Unmutes and enables the PWM output.
     */
    public void enable() {
        pwm.enable();
    }

    /**
     * Silences and disables the PWM output.
     */
    public void disable() {
        pwm.disable();
    }

    /**
     * Updates the frequency and duty cycle.
     *
     * @param periodNs The total duration of a single square-wave cycle in nanoseconds.
     * @param dutyCycleNs The active high duration in nanoseconds.
     * @throws IllegalArgumentException If duty cycle exceeds period.
     */
    public void setPulse(final long periodNs, final long dutyCycleNs) {
        if (dutyCycleNs > periodNs) {
            throw new IllegalArgumentException("Duty cycle (%d) cannot exceed period (%d)"
                    .formatted(dutyCycleNs, periodNs));
        }
        pwm.setPulse(periodNs, dutyCycleNs);
    }

    /**
     * Helper to play a specific frequency for a duration.
     *
     * @param periodNs The period of the note in nanoseconds.
     * @param durationMs How long to play the note in milliseconds.
     * @throws InterruptedException If the sleep is interrupted.
     */
    public void playTone(final long periodNs, final long durationMs) throws InterruptedException {
        // 50% duty cycle for a clean square wave tone
        setPulse(periodNs, periodNs / 2);
        enable();
        Thread.sleep(durationMs);
        disable();
    }

    /**
     * Safely silences the hardware and releases native resource bindings.
     * <p>
     * Ensures duty cycle is reset and PWM is disabled before closing the underlying transport to prevent "stuck notes."
     * </p>
     */
    @Override
    public void close() {
        if (log.isDebugEnabled()) {
            log.debug("Closing PassiveSpeaker and silencing output");
        }
        try {
            // Silence immediately
            pwm.setPulse(1000000L, 0L);
            pwm.disable();
        } catch (final Exception e) {
            log.warn("Error silencing PWM during speaker close: {}", e.getMessage());
        } finally {
            pwm.close();
        }
    }
}
