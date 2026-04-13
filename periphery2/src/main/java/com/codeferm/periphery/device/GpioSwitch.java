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
 * GPIO switch device with background polling, callback support, and software debouncing.
 * <p>
 * This class provides an event-driven abstraction for GPIO lines that do not support hardware edge detection (EINT). It manages a
 * background thread to monitor state changes and filters out mechanical contact chatter (debounce). If you are using a push button
 * module like in the 37 in 1 kits run - to 5v, S to GND and middle pin to GPIO.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class GpioSwitch implements AutoCloseable {

    /**
     * GPIO direction input constant.
     */
    private static final int GPIO_DIR_IN = 0;

    /**
     * Internal pull-down bias constant for c-periphery.
     */
    private static final int GPIO_BIAS_PULL_DOWN = 2;

    /**
     * Reentrant lock for thread-safe access to native handles and buffers.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Managed arena for native memory segments.
     */
    private final Arena arena;

    /**
     * Native handle for the GPIO character device.
     */
    private final MemorySegment handle;

    /**
     * Pre-allocated buffer for state reads to ensure zero-allocation polling.
     */
    private final MemorySegment stateBuffer;

    /**
     * Flag to control the lifecycle of the background monitoring thread.
     */
    private volatile boolean watching = false;

    /**
     * Initializes the GPIO switch with internal pull-down bias.
     *
     * @param device GPIO device path (e.g., "/dev/gpiochip0").
     * @param line GPIO line number (e.g., 77 for Pine A64).
     * @throws RuntimeException If the native GPIO device cannot be opened.
     */
    public GpioSwitch(final String device, final int line) {
        this.arena = Arena.ofShared();
        this.handle = arena.allocate(gpio_handle.layout());
        this.stateBuffer = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        final var cDevice = arena.allocateFrom(device);
        if (Periphery.gpio_open(handle, cDevice, line, GPIO_DIR_IN) < 0) {
            final var error = Periphery.gpio_errmsg(handle).getString(0);
            throw new RuntimeException("Failed to open GPIO %s line %d: %s".formatted(device, line, error));
        }
        // Apply bias to prevent ghosting on floating pins [2026-01-22]
        if (Periphery.gpio_set_bias(handle, GPIO_BIAS_PULL_DOWN) < 0) {
            log.warn("Hardware bias not supported on this pin; external resistor may be required.");
        }
        log.atDebug().log("GPIO Switch initialized on {} line {}", device, line);
    }

    /**
     * Synchronously read the current state of the switch.
     *
     * @return 1 for Pressed (High), 0 for Open (Low).
     */
    public int getValue() {
        lock.lock();
        try {
            if (Periphery.gpio_read(handle, stateBuffer) < 0) {
                final var error = Periphery.gpio_errmsg(handle).getString(0);
                throw new RuntimeException("GPIO read failed: %s".formatted(error));
            }
            return stateBuffer.get(ValueLayout.JAVA_BOOLEAN, 0) ? 1 : 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Starts a background thread to monitor state changes with software debouncing.
     * <p>
     * The callback is triggered only after the signal has remained stable for the specified debounce duration.
     * </p>
     *
     * @param pollIntervalMs Frequency to sample the hardware in milliseconds.
     * @param debounceMs Stability duration required to confirm a state change.
     * @param callback The consumer invoked with the validated state (0 or 1).
     */
    public void watch(final long pollIntervalMs, final long debounceMs, final Consumer<Integer> callback) {
        if (watching) {
            throw new IllegalStateException("Watch thread is already active.");
        }
        watching = true;
        final var thread = new Thread(() -> {
            log.info("Background watch thread started: {}ms poll, {}ms debounce.", pollIntervalMs, debounceMs);
            var lastValidValue = getValue();
            var lastChangeTime = System.nanoTime();
            final var debounceNanos = TimeUnit.MILLISECONDS.toNanos(debounceMs);
            while (watching && !Thread.currentThread().isInterrupted()) {
                final var currentValue = getValue();
                final var currentTime = System.nanoTime();
                // If hardware state differs from last confirmed stable value
                if (currentValue != lastValidValue) {
                    // Only accept the change if the debounce period has elapsed
                    if ((currentTime - lastChangeTime) > debounceNanos) {
                        lastValidValue = currentValue;
                        callback.accept(currentValue);
                    }
                    // Reset change timer on every detected transition to ensure stability
                    lastChangeTime = currentTime;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(pollIntervalMs);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            log.info("Background watch thread stopping.");
        }, "GpioWatch-Thread");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Stops monitoring and releases native GPIO resources.
     */
    @Override
    public void close() {
        watching = false;
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
