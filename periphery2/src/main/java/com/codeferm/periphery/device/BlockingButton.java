/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

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
 * This class provides a thread-safe abstraction for interacting with GPIO buttons. It utilizes kernel-level edge events and filters
 * out mechanical contact bounce by enforcing a Minimum Inter-arrival Time (MIT) based on kernel timestamps.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class BlockingButton extends AbstractDevice {

    private final ReentrantLock lock = new ReentrantLock();
    private final MemorySegment edgePtr;
    private final MemorySegment timestampPtr;

    @Getter
    private long debounceNs = 50_000_000L;
    private long lastFallingNs = 0;
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
     *
     * @param device GPIO device path.
     * @param line GPIO line number.
     * @throws RuntimeException if the GPIO device cannot be opened or configured.
     */
    public BlockingButton(final String device, final int line) {
        super(gpio_handle.layout());

        /*
         * Allocate 16 bytes total with 8-byte alignment using the inherited arena.
         * Ensures JAVA_LONG starts at offset 8 on a valid boundary.
         */
        final var eventBuffer = getArena().allocate(16, 8);
        this.edgePtr = eventBuffer.asSlice(0, ValueLayout.JAVA_INT.byteSize());
        this.timestampPtr = eventBuffer.asSlice(8, ValueLayout.JAVA_LONG.byteSize());

        final var cDevice = getArena().allocateFrom(device);

        // Open GPIO as input using consolidated error check
        checkError(Periphery.gpio_open(getHandle(), cDevice, line, Periphery.GPIO_DIR_IN()),
                String.format("Failed to open GPIO %s line %d", device, line));

        // Enable edge detection for both edges
        if (Periphery.gpio_set_edge(getHandle(), Periphery.GPIO_EDGE_BOTH()) < 0) {
            log.warn("Kernel edge detection unsupported for GPIO {} line {}.", device, line);
        }
    }

    /**
     * Configures the debounce threshold.
     *
     * @param millis Debounce time in milliseconds.
     */
    public void setDebounceMillis(final int millis) {
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
     *
     * @param timeoutMillis Max wait time (ms). Use -1 for infinite.
     * @return ButtonEvent or null if timeout occurs.
     */
    public ButtonEvent waitForEvent(final int timeoutMillis) {
        final var startTime = System.currentTimeMillis();
        var remainingTimeout = timeoutMillis;

        lock.lock();
        try {
            while (timeoutMillis == -1 || remainingTimeout > 0) {
                final var ret = Periphery.gpio_poll(getHandle(), remainingTimeout);

                if (ret <= 0) {
                    return null;
                }

                if (Periphery.gpio_read_event(getHandle(), edgePtr, timestampPtr) >= 0) {
                    final var edge = edgePtr.get(ValueLayout.JAVA_INT, 0);
                    final var ts = timestampPtr.get(ValueLayout.JAVA_LONG, 0);

                    if (isDebounced(edge, ts)) {
                        log.trace("Filtered bounce event: edge={}, ts={}", edge, ts);
                        if (timeoutMillis != -1) {
                            remainingTimeout = (int) (timeoutMillis - (System.currentTimeMillis() - startTime));
                            if (remainingTimeout <= 0) {
                                return null;
                            }
                        }
                        continue;
                    }
                    return new ButtonEvent(edge, ts);
                }
            }
        } finally {
            lock.unlock();
        }
        return null;
    }

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

    @Override
    protected void closeNative() {
        lock.lock();
        try {
            if (getHandle().address() != 0) {
                Periphery.gpio_close(getHandle());
            }
        } finally {
            lock.unlock();
        }
    }

    // Static Utility Methods remain same as they don't rely on instance state
    public static String formatTimestamp(final long timestamp) {
        return "%d.%09d".formatted(timestamp / 1_000_000_000L, timestamp % 1_000_000_000L);
    }

    public static String edgeToString(final int edge) {
        if (edge == Periphery.GPIO_EDGE_RISING()) {
            return "Rising";
        }
        if (edge == Periphery.GPIO_EDGE_FALLING()) {
            return "Falling";
        }
        return "Invalid";
    }
}
