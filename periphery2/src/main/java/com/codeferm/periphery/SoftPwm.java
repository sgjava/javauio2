/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery;

import com.codeferm.periphery.device.AbstractDevice;
import com.codeferm.periphery.device.PwmDevice;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;

/**
 * Software-based Pulse Width Modulation (PWM) implementation using FFM and a high-priority dedicated thread.
 * <p>
 * This class provides PWM functionality for GPIO pins that do not have hardware PWM support. It uses a precision busy-wait loop to
 * maintain timing accuracy and inherits automated safe-teardown from {@link AbstractDevice}.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class SoftPwm extends AbstractDevice implements PwmDevice {

    /**
     * GPIO direction output constant from c-periphery.
     */
    private static final int GPIO_DIR_OUT = 1;

    /**
     * Dedicated thread for generating the pulse signal.
     */
    private final Thread pulseThread;

    /**
     * Atomic flag to control the lifecycle of the pulse thread.
     */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * Atomic flag to enable or disable the signal output.
     */
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    /**
     * Lock to ensure thread-safe access to native write operations.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Current period in nanoseconds.
     */
    private volatile long periodNs = 10_000_000L;

    /**
     * Current duty cycle in nanoseconds.
     */
    private volatile long dutyCycleNs = 0L;

    /**
     * Constructs a software PWM controller for a specific GPIO line.
     *
     * @param device GPIO device path (e.g., "/dev/gpiochip0").
     * @param line GPIO line offset.
     * @throws RuntimeException If the GPIO device cannot be opened.
     */
    public SoftPwm(final String device, final int line) {
        // Passes layout up to handle automated registration and memory tracking
        super(gpio_handle.layout());

        final var cDevice = getArena().allocateFrom(device);

        if (Periphery.gpio_open(getHandle(), cDevice, line, GPIO_DIR_OUT) < 0) {
            final var error = Periphery.gpio_errmsg(getHandle()).getString(0);
            if (getArena().scope().isAlive()) {
                getArena().close();
            }
            throw new RuntimeException("Failed to open GPIO line %d: %s".formatted(line, error));
        }

        this.pulseThread = new Thread(this::pulseLoop, "SoftPwm-Line" + line);
        this.pulseThread.setPriority(Thread.MAX_PRIORITY);
        this.pulseThread.start();

        log.debug("Software PWM started on line {}", line);
    }

    /**
     * Enables the PWM signal output.
     */
    @Override
    public void enable() {
        enabled.set(true);
    }

    /**
     * Disables the PWM signal output and sets the pin to LOW.
     */
    @Override
    public void disable() {
        enabled.set(false);
        write(false);
    }

    /**
     * Sets the period and duty cycle for the software pulse.
     *
     * @param periodNs Total signal period in nanoseconds.
     * @param dutyCycleNs Signal high-time in nanoseconds.
     */
    @Override
    public void setPulse(final long periodNs, final long dutyCycleNs) {
        this.periodNs = periodNs;
        this.dutyCycleNs = dutyCycleNs;
    }

    /**
     * Internal pulse loop. Uses spin-waiting to minimize jitter.
     */
    private void pulseLoop() {
        while (running.get()) {
            if (enabled.get()) {
                final var p = periodNs;
                final var d = dutyCycleNs;

                if (d > 0) {
                    write(true);
                    busyWait(d);
                }
                if (d < p) {
                    write(false);
                    busyWait(p - d);
                }
            } else {
                // Yield CPU when disabled to avoid unnecessary power consumption
                Thread.onSpinWait();
            }
        }
    }

    /**
     * Writes the state to the GPIO pin using the native c-periphery call. Guarded with a ReentrantLock to secure segment safety
     * during thread cleanup.
     *
     * @param state True for HIGH, false for LOW.
     */
    private void write(final boolean state) {
        lock.lock();
        try {
            // Check scope health inside lock to prevent race conditions during close
            if (getHandle().address() != 0 && getArena().scope().isAlive()) {
                Periphery.gpio_write(getHandle(), state);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Performs a high-precision busy wait for the specified nanoseconds.
     *
     * @param ns Time to wait in nanoseconds.
     */
    private void busyWait(final long ns) {
        final var start = System.nanoTime();
        while (System.nanoTime() - start < ns && running.get()) {
            Thread.onSpinWait();
        }
    }

    /**
     * Template implementation called by AbstractDevice. Coordinates the safe destruction of the generator thread before dropping
     * file nodes.
     */
    @Override
    protected void closeNative() {
        log.debug("Closing Software PWM and stopping generator thread");

        // 1. Signal the pulse loop thread to break its execution boundaries
        running.set(false);
        enabled.set(false);

        // 2. Join the thread to guarantee it has left the spin-wait and write blocks
        try {
            if (pulseThread.isAlive()) {
                pulseThread.join(150);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while stopping software PWM thread: {}", e.getMessage());
        }

        // 3. Acquire lock to perform safe hardware termination and close handles
        lock.lock();
        try {
            if (getHandle().address() != 0) {
                Periphery.gpio_write(getHandle(), false);
                Periphery.gpio_close(getHandle());
                log.debug("Software PWM hardware line dropped LOW and closed cleanly.");
            }
        } finally {
            lock.unlock();
        }
    }
}
