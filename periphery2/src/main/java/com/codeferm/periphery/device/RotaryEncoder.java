/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;

/**
 * High-performance, interrupt-driven KY-040 Rotary Encoder driver using Java FFM.
 * <p>
 * This class uses kernel-level edge detection to eliminate polling. It handles Quadrature encoding for rotation and dual-edge
 * tracking for the push button.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class RotaryEncoder implements AutoCloseable {

    private static final int GPIO_DIR_IN = 0;
    private static final int EDGE_FALLING = 2;

    private final ReentrantLock lock = new ReentrantLock();
    private final Arena arena;

    // Native Handles
    private final MemorySegment clkHandle;
    private final MemorySegment dtHandle;
    private final MemorySegment swHandle;

    // Pre-allocated event buffers (Zero-Allocation Loop)
    private final MemorySegment eventBuffer;
    private final MemorySegment edgePtr;
    private final MemorySegment timestampPtr;

    private volatile boolean watching = false;
    private long lastRotationNs = 0;
    private long lastButtonNs = 0;

    public RotaryEncoder(final String device, final int clkLine, final int dtLine, final int swLine) {
        this.arena = Arena.ofShared();

        // Allocate handles
        this.clkHandle = arena.allocate(gpio_handle.layout());
        this.dtHandle = arena.allocate(gpio_handle.layout());
        this.swHandle = arena.allocate(gpio_handle.layout());

        // Setup 16-byte buffer for kernel events (Edge + Timestamp)
        this.eventBuffer = arena.allocate(16, 8);
        this.edgePtr = eventBuffer.asSlice(0, ValueLayout.JAVA_INT.byteSize());
        this.timestampPtr = eventBuffer.asSlice(8, ValueLayout.JAVA_LONG.byteSize());

        final var cDevice = arena.allocateFrom(device);

        // Open all lines
        openLine(clkHandle, cDevice, clkLine);
        openLine(dtHandle, cDevice, dtLine);
        openLine(swHandle, cDevice, swLine);

        // Arm interrupts
        setEdge(clkHandle);
        setEdge(dtHandle);
        setEdge(swHandle);

        log.debug("Rotary Encoder operational. CLK: {}, DT: {}, SW: {}", clkLine, dtLine, swLine);
    }

    private void openLine(final MemorySegment handle, final MemorySegment device, final int line) {
        if (Periphery.gpio_open(handle, device, line, GPIO_DIR_IN) < 0) {
            throw new RuntimeException("Failed to open GPIO: " + Periphery.gpio_errmsg(handle).getString(0));
        }
    }

    private void setEdge(final MemorySegment handle) {
        if (Periphery.gpio_set_edge(handle, Periphery.GPIO_EDGE_BOTH()) < 0) {
            log.warn("Kernel edge detection unsupported for handle at {}", handle.address());
        }
    }

    private int readValue(final MemorySegment handle) {
        lock.lock();
        try {
            Periphery.gpio_read(handle, edgePtr);
            return edgePtr.get(ValueLayout.JAVA_INT, 0) != 0 ? 1 : 0;
        } finally {
            lock.unlock();
        }
    }

    public void watch(final long debounceMs, final Consumer<Integer> rotationAction, final BiConsumer<Integer, Long> buttonAction) {
        if (watching) {
            throw new IllegalStateException("Watcher already active.");
        }
        watching = true;

        final var debounceNanos = TimeUnit.MILLISECONDS.toNanos(debounceMs);
        final var thread = new Thread(() -> {
            while (watching && !Thread.currentThread().isInterrupted()) {
                var eventProcessed = false;

                // Check CLK (Rotation)
                if (Periphery.gpio_poll(clkHandle, 0) > 0) {
                    if (Periphery.gpio_read_event(clkHandle, edgePtr, timestampPtr) >= 0) {
                        final var ts = timestampPtr.get(ValueLayout.JAVA_LONG, 0);
                        // Quadrature: only act on falling edge of CLK
                        if (edgePtr.get(ValueLayout.JAVA_INT, 0) == EDGE_FALLING && (ts - lastRotationNs) > debounceNanos) {
                            rotationAction.accept(readValue(dtHandle) == 1 ? 1 : -1);
                            lastRotationNs = ts;
                        }
                        eventProcessed = true;
                    }
                }

                // Check SW (Button)
                if (Periphery.gpio_poll(swHandle, 0) > 0) {
                    if (Periphery.gpio_read_event(swHandle, edgePtr, timestampPtr) >= 0) {
                        final var ts = timestampPtr.get(ValueLayout.JAVA_LONG, 0);
                        if ((ts - lastButtonNs) > debounceNanos) {
                            buttonAction.accept(edgePtr.get(ValueLayout.JAVA_INT, 0), ts);
                            lastButtonNs = ts;
                        }
                        eventProcessed = true;
                    }
                }

                if (!eventProcessed) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "Rotary-Watcher");

        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        watching = false;
        lock.lock();
        try {
            if (clkHandle.address() != 0) {
                Periphery.gpio_close(clkHandle);
            }
            if (dtHandle.address() != 0) {
                Periphery.gpio_close(dtHandle);
            }
            if (swHandle.address() != 0) {
                Periphery.gpio_close(swHandle);
            }
            if (arena.scope().isAlive()) {
                arena.close();
            }
        } finally {
            lock.unlock();
        }
    }
}
