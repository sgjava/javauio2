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
 * GPIO switch device with background polling, callback support, and software debouncing.
 * <p>
 * This class provides an event-driven abstraction for GPIO lines that do not support hardware edge detection (EINT). It manages a
 * background thread to monitor state changes and filters out mechanical contact chatter.
 * </p>
 * <p>
 * Extends {@link AbstractDevice} for deterministic memory management and zero-allocation polling.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class GpioSwitch extends AbstractDevice {

    /**
     * GPIO direction input constant.
     */
    private static final int GPIO_DIR_IN = 0;

    /**
     * Internal pull-down bias constant for c-periphery.
     */
    private static final int GPIO_BIAS_PULL_DOWN = 2;

    private final ReentrantLock lock = new ReentrantLock();
    private final MemorySegment stateBuffer;
    private volatile boolean watching = false;

    /**
     * Initializes the GPIO switch with internal pull-down bias.
     *
     * @param device GPIO device path (e.g., "/dev/gpiochip0").
     * @param line GPIO line number.
     * @throws RuntimeException If the native GPIO device cannot be opened.
     */
    public GpioSwitch(final String device, final int line) {
        super(gpio_handle.layout());

        // Allocate state buffer using the inherited arena
        this.stateBuffer = getArena().allocate(ValueLayout.JAVA_BOOLEAN);
        final var cDevice = getArena().allocateFrom(device);

        checkError(Periphery.gpio_open(getHandle(), cDevice, line, GPIO_DIR_IN),
                String.format("Failed to open GPIO %s line %d", device, line));

        // Apply bias to prevent ghosting on floating pins [2026-01-22]
        if (Periphery.gpio_set_bias(getHandle(), GPIO_BIAS_PULL_DOWN) < 0) {
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
            checkError(Periphery.gpio_read(getHandle(), stateBuffer), "GPIO read failed");
            return stateBuffer.get(ValueLayout.JAVA_BOOLEAN, 0) ? 1 : 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Starts a background thread to monitor state changes with software debouncing.
     *
     * @param pollIntervalMs Frequency to sample hardware (ms).
     * @param debounceMs Stability duration required to confirm a change (ms).
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

                if (currentValue != lastValidValue) {
                    if ((currentTime - lastChangeTime) > debounceNanos) {
                        lastValidValue = currentValue;
                        callback.accept(currentValue);
                    }
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
    protected void closeNative() {
        watching = false;
        lock.lock();
        try {
            if (getHandle().address() != 0) {
                Periphery.gpio_close(getHandle());
            }
        } finally {
            lock.unlock();
        }
    }
}
