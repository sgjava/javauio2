/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import com.codeferm.periphery.Pwm;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;

/**
 * Hardware-backed Passive Speaker implementation using Linux sysfs and FFM.
 * <p>
 * This class provides thread-safe control over a hardware PWM channel. It ensures that the hardware is explicitly silenced and
 * disabled during closure to prevent persistent signal output (stuck notes) on process termination.</p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class PassiveSpeaker implements PwmDevice {

    /**
     * Internal FFM wrapper for c-periphery PWM handle management.
     */
    private final Pwm pwm;

    /**
     * Constructs a thread-safe hardware passive speaker device instance.
     *
     * @param chip The Linux PWM chip index.
     * @param channel The Linux PWM channel index.
     */
    public PassiveSpeaker(final int chip, final int channel) {
        if (log.isDebugEnabled()) {
            log.debug("Initializing PassiveSpeaker on chip {}, channel {}", chip, channel);
        }
        this.pwm = new Pwm(chip, channel);
    }

    /**
     * Unmutes and enables the hardware clock output generator.
     */
    @Override
    public void enable() {
        synchronized (this.pwm) {
            this.pwm.enable();
        }
    }

    /**
     * Silences and disables the hardware clock output generator.
     */
    @Override
    public void disable() {
        synchronized (this.pwm) {
            this.pwm.disable();
        }
    }

    /**
     * Updates hardware clock period and duty pulse width.
     *
     * @param periodNs The total duration of a single square-wave cycle in nanoseconds.
     * @param dutyCycleNs The active high duration in nanoseconds.
     */
    @Override
    public void setPulse(final long periodNs, final long dutyCycleNs) {
        if (dutyCycleNs > periodNs) {
            throw new IllegalArgumentException(String.format(
                    "Duty cycle (%d) cannot exceed period (%d)", dutyCycleNs, periodNs));
        }
        synchronized (this.pwm) {
            final var handle = this.pwm.getHandle();
            Periphery.pwm_set_period_ns(handle, periodNs);
            Periphery.pwm_set_duty_cycle_ns(handle, dutyCycleNs);
        }
    }

    /**
     * Safely silences the hardware and unmaps FFM native memory models.
     * <p>
     * Specifically forces duty cycle to 0 and disables the clock to prevent the PWM peripheral from continuing to oscillate after
     * the JVM exits.</p>
     */
    @Override
    public void close() {
        if (log.isDebugEnabled()) {
            log.debug("Releasing native resource bindings and silencing hardware");
        }
        synchronized (this.pwm) {
            try {
                final var handle = this.pwm.getHandle();
                if (handle != null) {
                    // Force physical pin low to kill the sound immediately
                    Periphery.pwm_set_duty_cycle_ns(handle, 0L);
                    this.pwm.disable();
                }
            } catch (final Exception e) {
                log.error("Failed to silence hardware during close: {}", e.getMessage());
            } finally {
                this.pwm.close();
            }
        }
    }
}
