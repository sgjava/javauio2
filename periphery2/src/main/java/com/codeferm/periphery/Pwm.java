/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery;

import com.codeferm.periphery.device.PwmDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.locks.ReentrantLock;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.pwm_handle;

/**
 * c-periphery PWM wrapper functions for Linux userspace sysfs PWMs using FFM.
 * <p>
 * This implementation fulfills the {@link PwmDevice} contract, providing thread-safe, high-performance interaction with Linux PWM
 * sysfs via Project Panama.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class Pwm implements PwmDevice {

    /**
     * Successful operation constant from c-periphery.
     */
    public static final int PWM_SUCCESS = 0;

    /**
     * Lock for thread-safe hardware register access.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Arena for native memory lifecycle management.
     */
    private final Arena arena;

    /**
     * Native memory segment for the c-periphery PWM handle structure.
     */
    @Getter(AccessLevel.PUBLIC)
    private final MemorySegment handle;

    /**
     * Open the sysfs PWM with the specified chip and channel.
     *
     * @param chip PWM chip number.
     * @param channel PWM channel number.
     * @throws RuntimeException If handle allocation or opening fails.
     */
    public Pwm(final int chip, final int channel) {
        this.arena = Arena.ofShared();
        this.handle = arena.allocate(pwm_handle.layout());

        if (handle.address() == 0) {
            throw new RuntimeException("Failed to allocate native PWM handle memory");
        }

        if (Periphery.pwm_open(handle, chip, channel) != PWM_SUCCESS) {
            final var error = getErrorMessage();
            arena.close();
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
            checkError(Periphery.pwm_enable(handle), "enable");
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
            checkError(Periphery.pwm_disable(handle), "disable");
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
            checkError(Periphery.pwm_set_period_ns(handle, periodNs), "set_period_ns");
            checkError(Periphery.pwm_set_duty_cycle_ns(handle, dutyCycleNs), "set_duty_cycle_ns");
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
            checkError(Periphery.pwm_set_frequency(handle, frequency), "set_frequency");
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
            final var freqBox = arena.allocate(ValueLayout.JAVA_DOUBLE);
            checkError(Periphery.pwm_get_frequency(handle, freqBox), "get_frequency");
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
    private void checkError(final int result, final String op) {
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
        final var ptr = Periphery.pwm_errmsg(handle);
        return ptr.address() == 0 ? "Unknown error" : ptr.getString(0);
    }

    /**
     * Closes the PWM device, silences hardware, and releases native memory.
     */
    @Override
    public void close() {
        lock.lock();
        try {
            if (handle.address() != 0) {
                // Silence hardware on close to prevent stuck notes
                Periphery.pwm_set_duty_cycle_ns(handle, 0L);
                Periphery.pwm_disable(handle);
                Periphery.pwm_close(handle);
                log.debug("PWM closed");
            }
            if (arena.scope().isAlive()) {
                arena.close();
            }
        } finally {
            lock.unlock();
        }
    }
}
