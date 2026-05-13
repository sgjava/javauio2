/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;

/**
 * High-performance generic digital input sensor using Java Foreign Function & Memory (FFM) API.
 * <p>
 * This class provides a low-latency interface for any digital output module (typically LM393 comparator-based) such as sound
 * sensors, reed switches, or IR detectors. It utilizes zero-allocation polling by pre-allocating {@link MemorySegment} buffers in a
 * shared {@link Arena}.
 * </p>
 * <p>
 * <b>Key Features:</b>
 * <ul>
 * <li><b>State Validation:</b> Uses a trailing lockout window to debounce mechanical switches or mask acoustic echoes/comparator
 * chatter.</li>
 * <li><b>Thread Safety:</b> Employs {@link ReentrantLock} to gate access to the shared native handle and state buffer.</li>
 * <li><b>Resource Management:</b> Implements {@link AutoCloseable} for deterministic lifecycle management of native memory and file
 * descriptors.</li>
 * </ul>
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.1.0
 * @since 1.0.0
 */
@Slf4j
public class DigitalSensor implements AutoCloseable {

    /**
     * Constant for GPIO input direction.
     */
    private static final int GPIO_DIR_IN = 0;

    /**
     * Lock to synchronize access to the native {@code handle} and {@code stateBuffer}.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Shared arena for all native memory segments associated with this sensor.
     */
    private final Arena arena;

    /**
     * Native memory segment representing the {@code gpio_handle} struct.
     */
    private final MemorySegment handle;

    /**
     * Pre-allocated segment for reading boolean GPIO states without runtime allocation.
     */
    private final MemorySegment stateBuffer;

    /**
     * Lifecycle flag for the background monitoring thread.
     */
    private volatile boolean watching = false;

    /**
     * Constructs a new DigitalSensor and opens the specified native GPIO line.
     *
     * @param device The absolute path to the GPIO character device (e.g., "/dev/gpiochip0").
     * @param line The GPIO line number to monitor.
     * @throws RuntimeException If the native {@code gpio_open} call fails.
     */
    public DigitalSensor(final String device, final int line) {
        this.arena = Arena.ofShared();
        this.handle = this.arena.allocate(gpio_handle.layout());
        this.stateBuffer = this.arena.allocate(ValueLayout.JAVA_BOOLEAN);
        // Convert Java String to native C-string within the arena
        final var cDevice = this.arena.allocateFrom(device);
        if (Periphery.gpio_open(this.handle, cDevice, line, GPIO_DIR_IN) < 0) {
            final var errorMsg = Periphery.gpio_errmsg(this.handle).getString(0);
            throw new RuntimeException(String.format("Failed to open native GPIO %s Line %d: %s",
                    device, line, errorMsg));
        }
        log.atDebug().log("Digital Sensor initialized: {} [Line {}]", device, line);
    }

    /**
     * Performs a synchronous, thread-safe read of the current digital state.
     *
     * @return {@code 1} for Active (High/Logic 1), {@code 0} for Inactive (Low/Logic 0).
     */
    public int getValue() {
        this.lock.lock();
        try {
            Periphery.gpio_read(this.handle, this.stateBuffer);
            return this.stateBuffer.get(ValueLayout.JAVA_BOOLEAN, 0) ? 1 : 0;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Spawns a high-priority daemon thread to monitor state transitions.
     * <p>
     * The monitor captures every state change but only invokes the {@code callback} once the {@code lockoutMs} period has elapsed
     * since the last valid transition.
     * </p>
     *
     * @param pollIntervalMs The sampling frequency in milliseconds.
     * @param lockoutMs The dead-time window (ms) to ignore subsequent transitions.
     * @param callback The consumer for state transition events (1 or 0).
     * @throws IllegalStateException If a watch thread is already active for this instance.
     */
    public void watch(final long pollIntervalMs, final long lockoutMs, final Consumer<Integer> callback) {
        if (this.watching) {
            throw new IllegalStateException("Watch thread is already active");
        }
        this.watching = true;
        final var thread = new Thread(() -> {
            log.info("Digital watch started: {}ms poll / {}ms lockout.", pollIntervalMs, lockoutMs);
            var lastValidValue = getValue();
            var lockOutUntil = 0L;
            while (this.watching && !Thread.currentThread().isInterrupted()) {
                final var currentValue = this.getValue();
                final var currentTime = System.nanoTime();
                // Detect state transition (Edge Trigger)
                if (currentValue != lastValidValue) {
                    if (currentTime > lockOutUntil) {
                        lastValidValue = currentValue;
                        callback.accept(currentValue);
                        lockOutUntil = currentTime + TimeUnit.MILLISECONDS.toNanos(lockoutMs);
                    }
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(pollIntervalMs);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            log.info("Digital watch thread terminating.");
        }, "DigitalWatch-Thread");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Terminates monitoring and releases all native resources.
     * <p>
     * Shuts down the background thread, closes the native GPIO file descriptor, and invalidates the {@link Arena}.
     * </p>
     */
    @Override
    public void close() {
        this.watching = false;
        this.lock.lock();
        try {
            // Managed arena handles the cleanup of stateBuffer and handle segments
            try (this.arena) {
                if (this.handle.address() != 0) {
                    Periphery.gpio_close(this.handle);
                }
            }
            log.debug("Digital Sensor resources released.");
        } finally {
            this.lock.unlock();
        }
    }
}
