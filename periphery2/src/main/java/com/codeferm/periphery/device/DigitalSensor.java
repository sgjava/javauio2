/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;

/**
 * Universal digital sensor implementation using FFM for high-performance GPIO.
 * <p>
 * This class is designed for digital modules (Sound, Reed, IR) utilizing the LM393 comparator. It employs a background watch thread
 * with a trailing lockout window to mask mechanical bounce or acoustic chatter.
 * </p>
 * <p>
 * <b>Implementation Details:</b>
 * <ul>
 * <li><b>Inheritance:</b> Extends {@link AbstractDevice} to delegate native plumbing.</li>
 * <li><b>Zero-Allocation:</b> Reuses a pre-allocated {@code stateBuffer} for polling.</li>
 * <li><b>Thread Safety:</b> Uses a {@link ReentrantLock} to gate native handle access.</li>
 * </ul>
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class DigitalSensor extends AbstractDevice {

    /**
     * Constant for GPIO input direction.
     */
    private static final int GPIO_DIR_IN = 0;

    private final ReentrantLock lock = new ReentrantLock();
    private final MemorySegment stateBuffer;
    private volatile boolean watching = false;

    /**
     * Constructs a new DigitalSensor and opens the specified native GPIO line.
     *
     * @param device GPIO character device path (e.g., "/dev/gpiochip0").
     * @param line GPIO line number (e.g., 17).
     * @throws RuntimeException If the native GPIO open call fails.
     */
    public DigitalSensor(final String device, final int line) {
        super(gpio_handle.layout());

        // Pre-allocate buffer in the inherited arena for zero-allocation getValue()
        this.stateBuffer = getArena().allocate(ValueLayout.JAVA_BOOLEAN);
        final var cDevice = getArena().allocateFrom(device);

        checkError(Periphery.gpio_open(getHandle(), cDevice, line, GPIO_DIR_IN),
                String.format("Failed to open GPIO %s Line %d", device, line));

        log.debug("Digital Sensor active on line {}", line);
    }

    /**
     * Performs a thread-safe, synchronous read of the current digital state.
     *
     * @return 1 for High (3.3V), 0 for Low (GND).
     */
    public int getValue() {
        lock.lock();
        try {
            Periphery.gpio_read(getHandle(), stateBuffer);
            return stateBuffer.get(ValueLayout.JAVA_BOOLEAN, 0) ? 1 : 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Monitors state transitions using a daemon thread and a lockout window.
     * <p>
     * Triggers the provided callback immediately upon a valid state transition, then enters a "lockout" period where further
     * transitions are ignored.
     * </p>
     *
     * @param pollInterval Sampling frequency in milliseconds.
     * @param lockout The quiet duration (ms) required after an event.
     * @param callback Consumer invoked with the validated state (0 or 1).
     * @throws IllegalStateException If a watch thread is already running.
     */
    public void watch(final long pollInterval, final long lockout, final Consumer<Integer> callback) {
        if (this.watching) {
            throw new IllegalStateException("Watch thread is already active");
        }
        this.watching = true;

        final var thread = new Thread(() -> {
            log.info("Starting watch loop: {}ms poll, {}ms lockout.", pollInterval, lockout);
            var lastValue = getValue();
            var lockoutEnd = 0L;

            while (this.watching && !Thread.currentThread().isInterrupted()) {
                final var current = getValue();
                final var now = System.nanoTime();

                // Detect Edge Transition
                if (current != lastValue && now > lockoutEnd) {
                    lastValue = current;
                    callback.accept(current);
                    lockoutEnd = now + TimeUnit.MILLISECONDS.toNanos(lockout);
                }

                try {
                    TimeUnit.MILLISECONDS.sleep(pollInterval);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            log.info("Watch loop terminated.");
        }, "DigitalWatch-Thread");

        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Stops the watch thread and calls the native {@code gpio_close} function.
     */
    @Override
    protected void closeNative() {
        this.watching = false;
        if (getHandle().address() != 0) {
            Periphery.gpio_close(getHandle());
        }
    }
}
