/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import com.codeferm.periphery.Pwm;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;

/**
 * Hardware-backed PWM implementation using Linux sysfs and FFM.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class PwmLed implements PwmDevice {

    /**
     * Internal FFM wrapper for c-periphery PWM.
     */
    private final Pwm pwm;

    /**
     * Constructs a hardware PWM device.
     *
     * @param chip The PWM chip index.
     * @param channel The PWM channel index.
     */
    public PwmLed(final int chip, final int channel) {
        this.pwm = new Pwm(chip, channel);
    }

    @Override
    public void enable() {
        pwm.enable();
    }

    @Override
    public void disable() {
        pwm.disable();
    }

    @Override
    public void setPulse(final long periodNs, final long dutyCycleNs) {
        final var handle = pwm.getHandle();
        // Hardware PWM requires duty_cycle <= period
        Periphery.pwm_set_period_ns(handle, periodNs);
        Periphery.pwm_set_duty_cycle_ns(handle, dutyCycleNs);
    }

    @Override
    public void close() {
        pwm.close();
    }
}
