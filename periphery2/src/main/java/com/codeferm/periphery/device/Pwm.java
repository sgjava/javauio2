/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.pwm_handle;

/**
 * c-periphery PWM wrapper functions for Linux userspace sysfs PWMs using FFM.
 * <p>
 * This implementation replaces legacy JNI with Project Panama/FFM API for high-performance interaction with Linux PWM sysfs.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class Pwm implements AutoCloseable {

    /**
     * Successful operation constant.
     */
    public static final int PWM_SUCCESS = 0;

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

        // Allocate handle structure using jextract-generated layout
        this.handle = arena.allocate(pwm_handle.layout());

        if (handle.address() == 0) {
            throw new RuntimeException("Failed to allocate native PWM handle memory");
        }

        // Open device
        if (Periphery.pwm_open(handle, chip, channel) != PWM_SUCCESS) {
            final var error = getErrorMessage();
            arena.close();
            throw new RuntimeException(error);
        }

        log.debug("PWM opened: chip {}, channel {}", chip, channel);
    }

    /**
     * Enable the PWM output.
     */
    public void enable() {
        checkError(Periphery.pwm_enable(handle), "enable");
    }

    /**
     * Disable the PWM output.
     */
    public void disable() {
        checkError(Periphery.pwm_disable(handle), "disable");
    }

    /**
     * Sets the frequency in Hz.
     *
     * @param frequency Frequency in Hz.
     */
    public void setFrequency(final double frequency) {
        checkError(Periphery.pwm_set_frequency(handle, frequency), "set_frequency");
    }

    /**
     * Gets the current frequency in Hz.
     *
     * @return Frequency in Hz.
     */
    public double getFrequency() {
        final var freqBox = arena.allocate(ValueLayout.JAVA_DOUBLE);
        checkError(Periphery.pwm_get_frequency(handle, freqBox), "get_frequency");
        return freqBox.get(ValueLayout.JAVA_DOUBLE, 0);
    }

    /**
     * Sets the duty cycle as a ratio between 0.0 and 1.0.
     *
     * @param dutyCycle Ratio (0.0 to 1.0).
     */
    public void setDutyCycle(final double dutyCycle) {
        checkError(Periphery.pwm_set_duty_cycle(handle, dutyCycle), "set_duty_cycle");
    }

    /**
     * Gets the current duty cycle ratio.
     *
     * @return Ratio (0.0 to 1.0).
     */
    public double getDutyCycle() {
        final var dutyBox = arena.allocate(ValueLayout.JAVA_DOUBLE);
        checkError(Periphery.pwm_get_duty_cycle(handle, dutyBox), "get_duty_cycle");
        return dutyBox.get(ValueLayout.JAVA_DOUBLE, 0);
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
     * Closes the PWM device and releases native memory.
     */
    @Override
    public void close() {
        if (handle.address() != 0) {
            Periphery.pwm_close(handle);
            log.debug("PWM closed");
        }
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
