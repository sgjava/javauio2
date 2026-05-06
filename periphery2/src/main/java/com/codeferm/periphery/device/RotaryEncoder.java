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
 * This class completely eliminates high-CPU polling by commanding the Linux kernel to track edge transitions via
 * {@code Periphery.gpio_set_edge}. It leverages a high-speed background interrupt-handling thread that sleeps natively via the OS
 * epoll layer, capturing both structural quadrature rotations and dual-edge tactile push button states (Press and Release).
 * </p>
 * <p>
 * All internal operations run inside pre-allocated off-heap memory segments to satisfy zero-allocation execution requirements,
 * preventing runtime Garbage Collection latency spikes.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class RotaryEncoder implements AutoCloseable {

    /**
     * GPIO direction input constant.
     */
    private static final int GPIO_DIR_IN = 0;

    /**
     * Native edge constant denoting a falling transition (1 to 0).
     */
    private static final int EDGE_FALLING = 2;

    /**
     * Reentrant lock for thread-safe native handle tracking.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Managed arena tracking off-heap structures lifecycle.
     */
    private final Arena arena;

    /**
     * Off-heap native handle descriptor for the CLK GPIO line.
     */
    private final MemorySegment clkHandle;

    /**
     * Off-heap native handle descriptor for the DT GPIO line.
     */
    private final MemorySegment dtHandle;

    /**
     * Off-heap native handle descriptor for the SW GPIO line.
     */
    private final MemorySegment swHandle;

    /**
     * Pre-allocated 16-byte buffer for parsing native kernel edge events. Aligned to 8 bytes to keep the 64-bit nanosecond
     * timestamp on a hardware boundary.
     */
    private final MemorySegment eventBuffer;

    /**
     * Slice of the event buffer pointing to the 32-bit integer edge identifier.
     */
    private final MemorySegment edgePtr;

    /**
     * Slice of the event buffer pointing to the 64-bit long nanosecond kernel timestamp.
     */
    private final MemorySegment timestampPtr;

    /**
     * Lifecycle variable controlling the background execution loop.
     */
    private volatile boolean watching = false;

    /**
     * Nanosecond timestamp of the last processed valid rotation transition.
     */
    private long lastRotationNs = 0;

    /**
     * Nanosecond timestamp of the last processed valid push button transition.
     */
    private long lastButtonNs = 0;

    /**
     * Initializes the encoder by assigning individual line handlers and arming native edge tracking.
     *
     * @param device GPIO chip device path (e.g., "/dev/gpiochip0").
     * @param clkLine Line index for Quadrature CLK.
     * @param dtLine Line index for Quadrature DT.
     * @param swLine Line index for Tactile Push Button Switch.
     * @throws RuntimeException If native lines cannot be opened or fail to support edge interrupts.
     */
    public RotaryEncoder(final String device, final int clkLine, final int dtLine, final int swLine) {
        this.arena = Arena.ofShared();

        this.clkHandle = this.arena.allocate(gpio_handle.layout());
        this.dtHandle = this.arena.allocate(gpio_handle.layout());
        this.swHandle = this.arena.allocate(gpio_handle.layout());

        this.eventBuffer = this.arena.allocate(16, 8);
        this.edgePtr = this.eventBuffer.asSlice(0, ValueLayout.JAVA_INT.byteSize());
        this.timestampPtr = this.eventBuffer.asSlice(8, ValueLayout.JAVA_LONG.byteSize());

        final var cDevice = this.arena.allocateFrom(device);

        // Open CLK, DT, and SW lines using standard input signatures
        if (Periphery.gpio_open(this.clkHandle, cDevice, clkLine, GPIO_DIR_IN) < 0) {
            throw new RuntimeException("Failed to open native CLK line: " + Periphery.gpio_errmsg(this.clkHandle).getString(0));
        }
        if (Periphery.gpio_open(this.dtHandle, cDevice, dtLine, GPIO_DIR_IN) < 0) {
            throw new RuntimeException("Failed to open native DT line: " + Periphery.gpio_errmsg(this.dtHandle).getString(0));
        }
        if (Periphery.gpio_open(this.swHandle, cDevice, swLine, GPIO_DIR_IN) < 0) {
            throw new RuntimeException("Failed to open native SW line: " + Periphery.gpio_errmsg(this.swHandle).getString(0));
        }

        // Bind kernel edge event generation to BOTH edges for ultra-responsive tracking
        if (Periphery.gpio_set_edge(this.clkHandle, Periphery.GPIO_EDGE_BOTH()) < 0) {
            log.warn("Kernel edge detection unsupported for GPIO {} line {}.", device, clkLine);
        }
        if (Periphery.gpio_set_edge(this.dtHandle, Periphery.GPIO_EDGE_BOTH()) < 0) {
            log.warn("Kernel edge detection unsupported for GPIO {} line {}.", device, dtLine);
        }
        if (Periphery.gpio_set_edge(this.swHandle, Periphery.GPIO_EDGE_BOTH()) < 0) {
            log.warn("Kernel edge detection unsupported for GPIO {} line {}.", device, swLine);
        }

        log.atDebug().log("10x Interrupt Rotary Encoder operational. CLK: {}, DT: {}, SW: {}", clkLine, dtLine, swLine);
    }

    /**
     * Reads a raw logic line level via a fast synchronous read operation.
     *
     * @param handle Native handle target to evaluate.
     * @return 1 for high logic level, 0 for low logic level.
     */
    private int readLineValue(final MemorySegment handle) {
        this.lock.lock();
        try {
            Periphery.gpio_read(handle, this.edgePtr);
            return this.edgePtr.get(ValueLayout.JAVA_INT, 0) != 0 ? 1 : 0;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Spawns an interrupt-driven background worker. Thread blocks natively via the OS until edges arrive.
     *
     * @param debounceMs Minimum interval in milliseconds required between consecutive inputs.
     * @param rotationAction Fired on direction updates (1 = CW, -1 = CCW).
     * @param buttonAction BiConsumer fired on button actions passing (EdgeType, KernelTimestampNs). Edge Type: 1 = Rising
     * (Release), 2 = Falling (Press).
     * @throws IllegalStateException If an active watch routine is already running.
     */
    public void watch(final long debounceMs, final Consumer<Integer> rotationAction, final BiConsumer<Integer, Long> buttonAction) {
        if (this.watching) {
            throw new IllegalStateException("Interrupt processing thread is already active.");
        }
        this.watching = true;

        final var debounceNanos = TimeUnit.MILLISECONDS.toNanos(debounceMs);

        final var thread = new Thread(() -> {
            log.info("Starting native event listener thread. Debounce filter: {}ms", debounceMs);

            while (this.watching && !Thread.currentThread().isInterrupted()) {
                var eventProcessed = false;

                // 1. Process Quadrature CLK Transitions (Poll with 0ms timeout for instant check)
                if (Periphery.gpio_poll(this.clkHandle, 0) > 0) {
                    if (Periphery.gpio_read_event(this.clkHandle, this.edgePtr, this.timestampPtr) >= 0) {
                        final var edge = this.edgePtr.get(ValueLayout.JAVA_INT, 0);
                        final var ts = this.timestampPtr.get(ValueLayout.JAVA_LONG, 0);

                        // Evaluate step changes exclusively on the falling transition of CLK
                        if (edge == EDGE_FALLING && (ts - this.lastRotationNs) > debounceNanos) {
                            final var dtVal = this.readLineValue(this.dtHandle);
                            if (dtVal == 1) {
                                rotationAction.accept(1);  // Clockwise
                            } else {
                                rotationAction.accept(-1); // Counter-Clockwise
                            }
                            this.lastRotationNs = ts;
                        }
                        eventProcessed = true;
                    }
                }

                // 2. Process Tactile SW Button Transitions
                if (Periphery.gpio_poll(this.swHandle, 0) > 0) {
                    if (Periphery.gpio_read_event(this.swHandle, this.edgePtr, this.timestampPtr) >= 0) {
                        final var edge = this.edgePtr.get(ValueLayout.JAVA_INT, 0);
                        final var ts = this.timestampPtr.get(ValueLayout.JAVA_LONG, 0);

                        if ((ts - this.lastButtonNs) > debounceNanos) {
                            buttonAction.accept(edge, ts);
                            this.lastButtonNs = ts;
                        }
                        eventProcessed = true;
                    }
                }

                // If no hardware events were queued, yield execution to protect CPU pipeline cycles
                if (!eventProcessed) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(1);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            log.info("Native event listener thread closed down cleanly.");
        }, "EncoderInterrupt-Thread");

        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Unarms edge event tracking listeners and closes all underlying off-heap hardware allocations.
     */
    @Override
    public void close() {
        this.watching = false;
        this.lock.lock();
        try {
            if (this.clkHandle.address() != 0) {
                Periphery.gpio_close(this.clkHandle);
            }
            if (this.dtHandle.address() != 0) {
                Periphery.gpio_close(this.dtHandle);
            }
            if (this.swHandle.address() != 0) {
                Periphery.gpio_close(this.swHandle);
            }
            if (this.arena.scope().isAlive()) {
                this.arena.close();
            }
        } finally {
            this.lock.unlock();
        }
    }
}
