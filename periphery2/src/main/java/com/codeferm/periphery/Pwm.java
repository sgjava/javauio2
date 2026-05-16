/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery;

import com.codeferm.periphery.device.AbstractDevice;
import com.codeferm.periphery.device.PwmDevice;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.pwm_handle;

/**
 * c-periphery PWM wrapper functions for Linux userspace sysfs PWMs using FFM.
 * <p>
 * This implementation fulfills the {@link PwmDevice} contract, providing thread-safe, high-performance interaction with Linux PWM
 * sysfs via Project Panama. It inherits automated cleanup from {@link AbstractDevice}.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class Pwm extends AbstractDevice implements PwmDevice {

    /**
     * Successful operation constant from c-periphery.
     */
    public static final int PWM_SUCCESS = 0;

    /**
     * Lock for thread-safe hardware register access.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Open the sysfs PWM with the specified chip and channel.
     *
     * @param chip PWM chip number.
     * @param channel PWM channel number.
     * @throws RuntimeException If handle allocation or opening fails.
     */
    public Pwm(final int chip, final int channel) {
        // Passes the c-periphery struct layout up to register with HardwareRegistry and allocate native memory
        super(pwm_handle.layout());

        if (Periphery.pwm_open(getHandle(), chip, channel) != PWM_SUCCESS) {
            final var error = getErrorMessage();
            // Invalidate the inherited arena right away if initialization fails
            if (getArena().scope().isAlive()) {
                getArena().close();
            }
            throw new RuntimeException("Failed to open PWM chip %d channel %d: %s".formatted(chip, channel, error));
        }

        log.debug("PWM opened: chip {}, channel {}", chip, channel);
    }

    /**
     * Enables the PWM output signal.
     */
    @Override
    public void enable() {
        lock.lock();
        try {
            checkError(Periphery.pwm_enable(getHandle()), "enable");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Disables the PWM output signal.
     */
    @Override
    public void disable() {
        lock.lock();
        try {
            checkError(Periphery.pwm_disable(getHandle()), "disable");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the PWM pulse parameters in nanoseconds.
     *
     * @param periodNs Total duration of a single cycle.
     * @param dutyCycleNs Duration the signal is high.
     */
    @Override
    public void setPulse(final long periodNs, final long dutyCycleNs) {
        lock.lock();
        try {
            checkError(Periphery.pwm_set_period_ns(getHandle(), periodNs), "set_period_ns");
            checkError(Periphery.pwm_set_duty_cycle_ns(getHandle(), dutyCycleNs), "set_duty_cycle_ns");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the frequency in Hz.
     *
     * @param frequency Frequency in Hz.
     */
    public void setFrequency(final double frequency) {
        lock.lock();
        try {
            checkError(Periphery.pwm_set_frequency(getHandle(), frequency), "set_frequency");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the current frequency in Hz.
     *
     * @return Frequency in Hz.
     */
    public double getFrequency() {
        lock.lock();
        try {
            final var freqBox = getArena().allocate(ValueLayout.JAVA_DOUBLE);
            checkError(Periphery.pwm_get_frequency(getHandle(), freqBox), "get_frequency");
            return freqBox.get(ValueLayout.JAVA_DOUBLE, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Internal error checker for periphery return codes.
     *
     * @param result Return code from native function.
     * @param op Operation name for logging.
     */
    @Override
    protected void checkError(final int result, final String op) {
        if (result < PWM_SUCCESS) {
            throw new RuntimeException("PWM %s failed: %s".formatted(op, getErrorMessage()));
        }
    }

    /**
     * Retrieves a human-readable error message from the native handle.
     *
     * @return Error message string.
     */
    public String getErrorMessage() {
        final var ptr = Periphery.pwm_errmsg(getHandle());
        return ptr.address() == 0 ? "Unknown error" : ptr.getString(0);
    }

    /**
     * Template implementation called by AbstractDevice during close or emergency system shutdown. Enforces an absolute zero-energy
     * hardware state before cutting loose the native sysfs nodes.
     */
    @Override
    protected void closeNative() {
        lock.lock();
        try {
            if (getHandle().address() != 0) {
                // Force state down to zero energy first so kernel generator doesn't freeze 'ON'
                Periphery.pwm_set_duty_cycle_ns(getHandle(), 0L);
                Periphery.pwm_disable(getHandle());
                Periphery.pwm_close(getHandle());
                log.debug("PWM hardware safely de-energized and closed.");
            }
        } finally {
            lock.unlock();
        }
    }
}
