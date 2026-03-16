/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import com.codeferm.periphery.NativeLoader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;

/**
 * Blocking button device using pure FFM bindings.
 * <p>
 * This class provides a thread-safe abstraction for interacting with GPIO buttons. It supports both high-performance kernel-level
 * edge events (interrupts) and manual state polling. Native memory is pre-allocated with specific alignment to ensure
 * zero-allocation during event loops while satisfying FFM alignment constraints for 64-bit types.
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
     * Pre-allocated buffer for edge and timestamp.
     * <p>
     * Allocated as 16 bytes with 8-byte alignment:
     * <ul>
     * <li>Offset 0: edge (4 bytes, JAVA_INT)</li>
     * <li>Offset 4: padding (4 bytes)</li>
     * <li>Offset 8: timestamp (8 bytes, JAVA_LONG)</li>
     * </ul>
     * </p>
     */
    private final MemorySegment eventBuffer;

    /**
     * Slice of eventBuffer for the edge (int).
     */
    private final MemorySegment edgePtr;

    /**
     * Slice of eventBuffer for the timestamp (long).
     */
    private final MemorySegment timestampPtr;

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
     * Configures the GPIO as an input and attempts to enable edge detection. If edge detection is unsupported by the driver, a
     * warning is logged.
     * </p>
     *
     * @param device GPIO device path (e.g., "/dev/gpiochip4").
     * @param line GPIO line number.
     * @throws RuntimeException if the GPIO device cannot be opened.
     */
    public BlockingButton(final String device, final int line) {
        this.arena = Arena.ofShared();
        this.handle = arena.allocate(gpio_handle.layout());

        /* * To satisfy FFM alignment, the JAVA_LONG (8 bytes) must start at an 8-byte boundary.
         * We allocate 16 bytes total with an 8-byte alignment constraint.
         */
        this.eventBuffer = arena.allocate(16, 8);
        this.edgePtr = eventBuffer.asSlice(0, ValueLayout.JAVA_INT.byteSize());
        this.timestampPtr = eventBuffer.asSlice(8, ValueLayout.JAVA_LONG.byteSize());

        final var cDevice = arena.allocateFrom(device);

        // Open GPIO as input
        if (Periphery.gpio_open(handle, cDevice, line, Periphery.GPIO_DIR_IN()) < 0) {
            final var error = Periphery.gpio_errmsg(handle).getString(0);
            throw new RuntimeException("Failed to open GPIO %s line %d: %s".formatted(device, line, error));
        }

        // Attempt to set edge detection (fail-soft for hardware that only supports polling)
        if (Periphery.gpio_set_edge(handle, Periphery.GPIO_EDGE_BOTH()) < 0) {
            log.warn("Kernel edge detection unsupported for GPIO {} line {}.", device, line);
        }
    }

    /**
     * Configures the edge detection type using native periphery constants.
     *
     * @param edge Edge constant (e.g., Periphery.GPIO_EDGE_RISING()).
     * @return 0 on success, or a negative error code on failure.
     */
    public final int setEdgeDetection(final int edge) {
        lock.lock();
        try {
            return Periphery.gpio_set_edge(handle, edge);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wait for a button event within the specified timeout.
     * <p>
     * This method blocks until an edge is detected or the timeout is reached.
     * </p>
     *
     * @param timeoutMillis Maximum wait time in milliseconds. Use -1 for infinite.
     * @return ButtonEvent or null if timeout occurs.
     */
    public ButtonEvent waitForEvent(final int timeoutMillis) {
        lock.lock();
        try {
            // gpio_poll returns 1 for event, 0 for timeout, < 0 for error
            final var ret = Periphery.gpio_poll(handle, timeoutMillis);
            if (ret > 0) {
                if (Periphery.gpio_read_event(handle, edgePtr, timestampPtr) >= 0) {
                    return new ButtonEvent(
                            edgePtr.get(ValueLayout.JAVA_INT, 0),
                            timestampPtr.get(ValueLayout.JAVA_LONG, 0)
                    );
                }
            }
        } finally {
            lock.unlock();
        }
        return null;
    }

    /**
     * Reads the current logical state of the GPIO line.
     * <p>
     * Uses JAVA_INT for the value pointer as libperiphery expects an int* for the state.
     * </p>
     *
     * @return True if the line is high, false if low.
     * @throws RuntimeException if the read operation fails.
     */
    public boolean readValue() {
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
    public void close() {
        lock.lock();
        try {
            try (arena) {
                if (handle.address() != 0) {
                    Periphery.gpio_close(handle);
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
