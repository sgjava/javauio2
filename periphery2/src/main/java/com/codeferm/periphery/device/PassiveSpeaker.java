/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import lombok.extern.slf4j.Slf4j;

/**
 * Passive Speaker implementation supporting both hardware and software PWM.
 * <p>
 * Provides high-level control over a speaker via a {@link PwmDevice} transport. Ensures lifecycle safety by muting and disabling
 * the underlying transport upon closure to prevent "stuck" tones.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class PassiveSpeaker implements AutoCloseable {

    /**
     * The underlying PWM transport.
     */
    private final PwmDevice pwm;

    /**
     * Constructs a PassiveSpeaker using a unified PWM transport.
     *
     * @param pwm The PWM implementation (Hardware or Software).
     */
    public PassiveSpeaker(final PwmDevice pwm) {
        log.atDebug().log("Initializing PassiveSpeaker with transport: {}",
                pwm.getClass().getSimpleName());
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
     * @param periodNs Total duration of a square-wave cycle in nanoseconds.
     * @param dutyCycleNs Active high duration in nanoseconds.
     * @throws IllegalArgumentException If duty cycle exceeds period.
     */
    public void setPulse(final long periodNs, final long dutyCycleNs) {
        if (dutyCycleNs > periodNs) {
            throw new IllegalArgumentException(
                    "Duty cycle (%d) cannot exceed period (%d)".formatted(dutyCycleNs, periodNs));
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
        try {
            Thread.sleep(durationMs);
        } finally {
            // Ensure we stop the tone even if the thread is interrupted
            disable();
        }
    }

    /**
     * Safely silences hardware and releases native resource bindings.
     * <p>
     * Mutes duty cycle and disables PWM before calling close on the transport.
     * </p>
     */
    @Override
    public void close() {
        log.atDebug().log("Closing PassiveSpeaker and silencing output");
        try {
            // Attempt to silence immediately
            pwm.setPulse(1_000_000L, 0L);
            pwm.disable();
        } catch (final Exception e) {
            log.warn("Error silencing PWM during speaker close: {}", e.getMessage());
        } finally {
            pwm.close();
        }
    }
}
