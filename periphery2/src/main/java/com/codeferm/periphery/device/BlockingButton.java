/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

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
 * Provides a thread-safe abstraction for waiting on GPIO edge events. Pre-allocating native memory for event data to ensure
 * zero-allocation during the polling loop.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class BlockingButton implements AutoCloseable {

    private static final int GPIO_DIR_IN = 0;
    private static final int GPIO_EDGE_RISING = 1;
    private static final int GPIO_EDGE_FALLING = 2;
    private static final int GPIO_EDGE_BOTH = 3;

    private final ReentrantLock lock = new ReentrantLock();
    private final Arena arena;
    private final MemorySegment handle;

    /**
     * Pre-allocated buffer for edge (4 bytes) and timestamp (8 bytes).
     */
    private final MemorySegment eventBuffer;
    private final MemorySegment edgePtr;
    private final MemorySegment timestampPtr;

    /**
     * Data record for button events.
     */
    public record ButtonEvent(int edge, long timestamp) {

    }

    /**
     * Initialize button on specified GPIO device and line.
     *
     * @param device GPIO device path.
     * @param line GPIO line number.
     */
    public BlockingButton(final String device, final int line) {
        this.arena = Arena.ofShared();
        this.handle = arena.allocate(gpio_handle.layout());

        // Allocate space for 1 int and 1 long
        this.eventBuffer = arena.allocate(ValueLayout.JAVA_INT.byteSize() + ValueLayout.JAVA_LONG.byteSize());
        this.edgePtr = eventBuffer.asSlice(0, ValueLayout.JAVA_INT.byteSize());
        this.timestampPtr = eventBuffer.asSlice(ValueLayout.JAVA_INT.byteSize(), ValueLayout.JAVA_LONG.byteSize());

        final var cDevice = arena.allocateFrom(device);
        if (Periphery.gpio_open(handle, cDevice, line, GPIO_DIR_IN) < 0) {
            final var error = Periphery.gpio_errmsg(handle).getString(0);
            throw new RuntimeException("Failed to open GPIO %s line %d: %s".formatted(device, line, error));
        }

        // Default to monitoring both edges
        setEdgeDetection(GPIO_EDGE_BOTH);
    }

    /**
     * Configures the edge detection type.
     *
     * @param edge Edge constant (RISING, FALLING, BOTH).
     */
    public final void setEdgeDetection(final int edge) {
        lock.lock();
        try {
            Periphery.gpio_set_edge(handle, edge);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wait for a button event within the specified timeout.
     *
     * @param timeoutMillis Maximum wait time in milliseconds.
     * @return ButtonEvent or null if timeout.
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

    public static String formatTimestamp(final long timestamp) {
        return "%d.%09d".formatted(timestamp / 1_000_000_000L, timestamp % 1_000_000_000L);
    }

    public static String edgeToString(final int edge) {
        return switch (edge) {
            case GPIO_EDGE_RISING ->
                "Rising";
            case GPIO_EDGE_FALLING ->
                "Falling";
            default ->
                "Invalid";
        };
    }

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
