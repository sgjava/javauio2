/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import com.codeferm.periphery.NativeLoader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.locks.ReentrantLock;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;

/**
 * Blocking button device using pure FFM bindings with integrated software debounce.
 * <p>
 * This class provides a thread-safe abstraction for interacting with GPIO buttons. It utilizes kernel-level edge events
 * (interrupts) and filters out mechanical contact bounce (chatter) by enforcing a Minimum Inter-arrival Time (MIT) based on kernel
 * timestamps.
 * </p>
 * <p>
 * The {@link #waitForEvent(int)} method is designed to be resilient; it will continue to poll the hardware until a valid
 * (non-debounced) event occurs or the specified timeout expires.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class BlockingButton implements AutoCloseable {

    static {
        // Load the native library for underlying FFM hardware access
        NativeLoader.load();
    }

    /**
     * Reentrant lock for thread-safe access to the GPIO hardware.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Arena for managing native memory lifecycle.
     */
    private final Arena arena;

    /**
     * Handle to the GPIO device.
     */
    private final MemorySegment handle;

    /**
     * Slice of eventBuffer for the edge (int).
     */
    private final MemorySegment edgePtr;

    /**
     * Slice of eventBuffer for the timestamp (long).
     */
    private final MemorySegment timestampPtr;

    /**
     * Debounce threshold in nanoseconds. Default is 50ms.
     */
    @Getter
    private long debounceNs = 50_000_000L;

    /**
     * Last valid falling edge timestamp in nanoseconds.
     */
    private long lastFallingNs = 0;

    /**
     * Last valid rising edge timestamp in nanoseconds.
     */
    private long lastRisingNs = 0;

    /**
     * Data record for button events.
     *
     * @param edge The edge type detected (Rising or Falling).
     * @param timestamp The kernel-provided nanosecond timestamp.
     */
    public record ButtonEvent(int edge, long timestamp) {

    }

    /**
     * Initialize button on specified GPIO device and line.
     * <p>
     * Configures the GPIO as an input and enables edge detection for both rising and falling edges. Native memory is pre-allocated
     * with 8-byte alignment to satisfy FFM constraints.
     * </p>
     *
     * @param device GPIO device path (e.g., "/dev/gpiochip0").
     * @param line GPIO line number.
     * @throws RuntimeException if the GPIO device cannot be opened or configured.
     */
    public BlockingButton(final String device, final int line) {
        this.arena = Arena.ofShared();
        this.handle = arena.allocate(gpio_handle.layout());

        /*
         * Allocate 16 bytes total with an 8-byte alignment constraint to ensure
         * JAVA_LONG starts at offset 8 on a valid boundary.
         */
        final var eventBuffer = arena.allocate(16, 8);
        this.edgePtr = eventBuffer.asSlice(0, ValueLayout.JAVA_INT.byteSize());
        this.timestampPtr = eventBuffer.asSlice(8, ValueLayout.JAVA_LONG.byteSize());

        final var cDevice = arena.allocateFrom(device);

        // Open GPIO as input
        if (Periphery.gpio_open(handle, cDevice, line, Periphery.GPIO_DIR_IN()) < 0) {
            final var error = Periphery.gpio_errmsg(handle).getString(0);
            throw new RuntimeException("Failed to open GPIO %s line %d: %s".formatted(device, line, error));
        }

        // Enable edge detection for both edges
        if (Periphery.gpio_set_edge(handle, Periphery.GPIO_EDGE_BOTH()) < 0) {
            log.warn("Kernel edge detection unsupported for GPIO {} line {}.", device, line);
        }
    }

    /**
     * Configures the debounce threshold.
     *
     * @param millis Debounce time in milliseconds (typically 20-100ms).
     */
    public final void setDebounceMillis(final int millis) {
        lock.lock();
        try {
            this.debounceNs = millis * 1_000_000L;
            log.debug("Debounce threshold set to {} ms", millis);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wait for a valid button event within the specified timeout.
     * <p>
     * This method blocks until a non-debounced edge is detected or the timeout is reached. If an edge is detected but rejected by
     * the debounce filter, the method will automatically resume waiting for the remainder of the timeout period.
     * </p>
     *
     * @param timeoutMillis Maximum wait time in milliseconds. Use -1 for infinite.
     * @return ButtonEvent or null if a genuine timeout occurs.
     */
    public final ButtonEvent waitForEvent(final int timeoutMillis) {
        final var startTime = System.currentTimeMillis();
        var remainingTimeout = timeoutMillis;

        lock.lock();
        try {
            while (timeoutMillis == -1 || remainingTimeout > 0) {
                final var ret = Periphery.gpio_poll(handle, remainingTimeout);

                if (ret <= 0) {
                    return null; // Hardware timeout or error
                }

                if (Periphery.gpio_read_event(handle, edgePtr, timestampPtr) >= 0) {
                    final var edge = edgePtr.get(ValueLayout.JAVA_INT, 0);
                    final var ts = timestampPtr.get(ValueLayout.JAVA_LONG, 0);

                    if (isDebounced(edge, ts)) {
                        log.trace("Filtered bounce event: edge={}, ts={}", edge, ts);
                        // Recalculate remaining time if not infinite
                        if (timeoutMillis != -1) {
                            remainingTimeout = (int) (timeoutMillis - (System.currentTimeMillis() - startTime));
                            if (remainingTimeout <= 0) {
                                return null;
                            }
                        }
                        continue; // Keep polling
                    }

                    return new ButtonEvent(edge, ts);
                }
            }
        } finally {
            lock.unlock();
        }
        return null;
    }

    /**
     * Internal check to see if an edge event is a mechanical bounce.
     *
     * * @param edge The detected edge.
     * @param ts The kernel timestamp in nanoseconds.
     * @return True if the event should be ignored.
     */
    private boolean isDebounced(final int edge, final long ts) {
        if (edge == Periphery.GPIO_EDGE_FALLING()) {
            if ((ts - lastFallingNs) < debounceNs) {
                return true;
            }
            lastFallingNs = ts;
        } else if (edge == Periphery.GPIO_EDGE_RISING()) {
            if ((ts - lastRisingNs) < debounceNs) {
                return true;
            }
            lastRisingNs = ts;
        }
        return false;
    }

    /**
     * Reads the current logical state of the GPIO line.
     *
     * @return True if the line is high, false if low.
     * @throws RuntimeException if the read operation fails.
     */
    public final boolean readValue() {
        lock.lock();
        try (final var local = Arena.ofConfined()) {
            final var valPtr = local.allocate(ValueLayout.JAVA_INT);
            if (Periphery.gpio_read(handle, valPtr) < 0) {
                final var error = Periphery.gpio_errmsg(handle).getString(0);
                throw new RuntimeException("Failed to read GPIO state: %s".formatted(error));
            }
            return valPtr.get(ValueLayout.JAVA_INT, 0) != 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Formats a nanosecond timestamp into a seconds.nanoseconds string.
     *
     * @param timestamp Nanosecond timestamp.
     * @return Formatted string.
     */
    public static String formatTimestamp(final long timestamp) {
        return "%d.%09d".formatted(timestamp / 1_000_000_000L, timestamp % 1_000_000_000L);
    }

    /**
     * Converts a native edge integer to a human-readable string.
     *
     * @param edge The native edge constant from Periphery.
     * @return String representation ("Rising", "Falling", or "Invalid").
     */
    public static String edgeToString(final int edge) {
        if (edge == Periphery.GPIO_EDGE_RISING()) {
            return "Rising";
        } else if (edge == Periphery.GPIO_EDGE_FALLING()) {
            return "Falling";
        }
        return "Invalid";
    }

    /**
     * Closes the GPIO device and releases all associated native memory resources.
     */
    @Override
    public final void close() {
        lock.lock();
        try {
            if (handle.address() != 0) {
                Periphery.gpio_close(handle);
            }
            if (arena.scope().isAlive()) {
                arena.close();
            }
        } finally {
            lock.unlock();
        }
    }
}
