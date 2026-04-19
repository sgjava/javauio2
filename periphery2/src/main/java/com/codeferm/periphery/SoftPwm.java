/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery;

import com.codeferm.periphery.device.PwmDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;

/**
 * Software-based Pulse Width Modulation (PWM) implementation using FFM and a high-priority dedicated thread.
 * <p>
 * This class provides PWM functionality for GPIO pins that do not have hardware PWM support. It uses a precision busy-wait loop to
 * maintain timing accuracy.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class SoftPwm implements PwmDevice {

    /**
     * GPIO direction output constant.
     */
    private static final int GPIO_DIR_OUT = 1;

    /**
     * Native memory arena for handle allocation.
     */
    private final Arena arena;

    /**
     * Native memory segment for the c-periphery GPIO handle.
     */
    private final MemorySegment handle;

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
        this.arena = Arena.ofShared();
        this.handle = arena.allocate(gpio_handle.layout());
        final var cDevice = arena.allocateFrom(device);

        if (Periphery.gpio_open(handle, cDevice, line, GPIO_DIR_OUT) < 0) {
            final var error = Periphery.gpio_errmsg(handle).getString(0);
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
     * Writes the state to the GPIO pin using the native c-periphery call.
     *
     * @param state True for HIGH, false for LOW.
     */
    private void write(final boolean state) {
        lock.lock();
        try {
            Periphery.gpio_write(handle, state);
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
        while (System.nanoTime() - start < ns) {
            Thread.onSpinWait();
        }
    }

    /**
     * Stops the pulse thread, releases the native GPIO handle, and closes the arena.
     */
    @Override
    public void close() {
        log.debug("Closing Software PWM");
        running.set(false);
        enabled.set(false);

        try {
            if (pulseThread.isAlive()) {
                pulseThread.join(100);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while stopping PWM thread");
        }

        lock.lock();
        try {
            if (handle.address() != 0) {
                Periphery.gpio_write(handle, false);
                Periphery.gpio_close(handle);
            }
            arena.close();
        } finally {
            lock.unlock();
        }
    }
}
