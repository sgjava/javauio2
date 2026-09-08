/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery;

import com.codeferm.periphery.device.AbstractDevice;
import com.codeferm.periphery.device.PwmDevice;
import java.lang.foreign.Arena;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;

/**
 * Software PWM implementation using a GPIO and a dedicated high-priority thread via Project Panama FFM.
 * <p>
 * Logical states: true = ON, false = OFF. When inverted is true, logical ON maps to GPIO LOW and logical OFF maps to GPIO HIGH. It
 * inherits automated lifecycle cleanup from {@link AbstractDevice}.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class SoftPwm extends AbstractDevice implements PwmDevice {

    /**
     * Successful operation constant from c-periphery.
     */
    public static final int GPIO_SUCCESS = 0;

    /**
     * Lock for thread-safe state and timing synchronization.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Flag indicating whether the software thread loop should execute.
     */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * Flag indicating whether the PWM output signal is currently enabled.
     */
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    /**
     * Specifies if the GPIO line is active-low (inverted).
     */
    private final boolean inverted;

    /**
     * Dedicated high-priority background thread handling signal timing.
     */
    private final Thread thread;

    /**
     * Signal period in nanoseconds.
     */
    private volatile long periodNs = 1_000_000L;

    /**
     * Signal duty cycle width in nanoseconds.
     */
    private volatile long dutyCycleNs = 0L;

    /**
     * Creates a non-inverted software PWM instance.
     *
     * @param device GPIO chip/device path (e.g., "/dev/gpiochip0").
     * @param line GPIO line offset.
     */
    public SoftPwm(final String device, final int line) {
        this(device, line, false);
    }

    /**
     * Creates a software PWM instance with explicit inversion control.
     *
     * @param device GPIO chip/device path (e.g., "/dev/gpiochip0").
     * @param line GPIO line offset.
     * @param inverted True if the GPIO circuit is active-low.
     */
    public SoftPwm(
            final String device,
            final int line,
            final boolean inverted
    ) {
        super(gpio_handle.layout());

        this.inverted = inverted;

        try (final var arena = Arena.ofConfined()) {
            final var deviceSegment = arena.allocateFrom(device);
            // Pass direction constant according to c-periphery bindings (GPIO_DIR_OUT)
            final var result = Periphery.gpio_open(
                    getHandle(),
                    deviceSegment,
                    line,
                    Periphery.GPIO_DIR_OUT()
            );

            if (result != GPIO_SUCCESS) {
                final var error = getErrorMessage();
                if (getArena().scope().isAlive()) {
                    getArena().close();
                }
                throw new RuntimeException("Failed to open GPIO device %s line %d: %s".formatted(device, line, error));
            }
        }

        // Start in the logical OFF state.
        write(false);

        thread = Thread.ofPlatform()
                .name("soft-pwm")
                .priority(Thread.MAX_PRIORITY)
                .start(this::run);

        log.debug("Software PWM initialized on device {}, line {}, inverted: {}", device, line, inverted);
    }

    /**
     * Enables the software PWM output signal.
     */
    @Override
    public void enable() {
        lock.lock();
        try {
            enabled.set(true);
            log.debug("Software PWM enabled");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Disables the software PWM output and forces the logical OFF state.
     */
    @Override
    public void disable() {
        lock.lock();
        try {
            enabled.set(false);
            write(false);
            log.debug("Software PWM disabled");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the PWM period and duty cycle parameters in nanoseconds.
     *
     * @param periodNs Total period of the signal in nanoseconds.
     * @param dutyCycleNs High-time of the signal in nanoseconds.
     */
    @Override
    public void setPulse(
            final long periodNs,
            final long dutyCycleNs
    ) {
        if (periodNs <= 0) {
            throw new IllegalArgumentException(
                    "periodNs must be greater than zero"
            );
        }

        if (dutyCycleNs < 0 || dutyCycleNs > periodNs) {
            throw new IllegalArgumentException(
                    "dutyCycleNs must be between 0 and periodNs"
            );
        }

        this.periodNs = periodNs;
        this.dutyCycleNs = dutyCycleNs;
    }

    /**
     * Returns whether this software PWM transport is active-low.
     *
     * @return True if inverted.
     */
    public boolean isInverted() {
        return inverted;
    }

    /**
     * Background worker thread managing high-precision software signal pulsing.
     */
    private void run() {
        while (running.get()) {

            if (!enabled.get()) {
                write(false);
                Thread.onSpinWait();
                continue;
            }

            final var period = periodNs;
            final var duty = dutyCycleNs;

            /*
             * 0% duty = logically OFF.
             */
            if (duty <= 0) {
                write(false);
                waitNanos(period);
                continue;
            }

            /*
             * 100% duty = logically ON.
             */
            if (duty >= period) {
                write(true);
                waitNanos(period);
                continue;
            }

            final var start = System.nanoTime();

            // Logical ON.
            write(true);

            // ON duration.
            waitUntil(start + duty);

            // Logical OFF.
            write(false);

            // OFF duration.
            waitUntil(start + period);
        }

        // Always leave GPIO in logical OFF state upon thread exit.
        write(false);
    }

    /**
     * Busy-wait for the specified duration to optimize real-time timing accuracy.
     *
     * @param nanos Duration in nanoseconds.
     */
    private void waitNanos(final long nanos) {
        final var start = System.nanoTime();
        waitUntil(start + nanos);
    }

    /**
     * Busy-wait until an absolute System.nanoTime() deadline.
     *
     * @param deadline Target absolute nanosecond timestamp.
     */
    private void waitUntil(final long deadline) {
        while (running.get()) {
            final var remaining = deadline - System.nanoTime();

            if (remaining <= 0) {
                return;
            }

            Thread.onSpinWait();
        }
    }

    /**
     * Writes a logical GPIO state, taking polarity inversion into account.
     *
     * @param state Logical state (true = ON, false = OFF).
     */
    private void write(final boolean state) {
        final var physicalState = inverted ? !state : state;

        checkError(
                Periphery.gpio_write(
                        getHandle(),
                        physicalState
                ),
                "gpio_write"
        );
    }

    /**
     * Retrieves a human-readable error message from the native handle.
     *
     * @return Error message string.
     */
    public String getErrorMessage() {
        final var ptr = Periphery.gpio_errmsg(getHandle());
        return ptr.address() == 0 ? "Unknown error" : ptr.getString(0);
    }

    /**
     * Checks c-periphery return codes and throws runtime exceptions on failure.
     *
     * @param result Native return code.
     * @param op Operation name for context.
     */
    @Override
    protected void checkError(final int result, final String op) {
        if (result < GPIO_SUCCESS) {
            throw new RuntimeException("GPIO %s failed: %s".formatted(op, getErrorMessage()));
        }
    }

    /**
     * Stops the worker thread, forces GPIO OFF, and releases native resources safely via {@link AbstractDevice}.
     */
    @Override
    protected void closeNative() {
        lock.lock();
        try {
            if (getHandle().address() != 0) {
                running.set(false);
                enabled.set(false);

                if (Thread.currentThread() != thread) {
                    try {
                        thread.join(150);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                // Force logical OFF; write() handles polarity.
                write(false);

                Periphery.gpio_close(getHandle());
                log.debug("Software PWM GPIO closed.");
            }
        } finally {
            lock.unlock();
        }
    }
}
