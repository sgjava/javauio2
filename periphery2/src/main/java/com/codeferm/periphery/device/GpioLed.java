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
 * LED device using pure FFM bindings to c-periphery.
 * <p>
 * High-performance, thread-safe control of a GPIO line. This implementation uses pre-allocated native memory segments for hardware
 * handles and state retrieval.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class GpioLed implements AutoCloseable {

    /**
     * GPIO direction out constant from c-periphery.
     */
    private static final int GPIO_DIR_OUT = 1;

    /**
     * Lock for thread-safe hardware access.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Arena for managing native memory.
     */
    private final Arena arena;

    /**
     * Native handle to the GPIO line.
     */
    private final MemorySegment handle;

    /**
     * Pre-allocated buffer for state reading to avoid GC pressure.
     */
    private final MemorySegment stateBuffer;

    /**
     * Constructor for GpioLed using jextract bindings.
     *
     * @param device GPIO device path (e.g., "/dev/gpiochip0").
     * @param line GPIO line number.
     * @throws RuntimeException if the GPIO cannot be opened.
     */
    public GpioLed(final String device, final int line) {
        this.arena = Arena.ofShared();
        this.handle = arena.allocate(gpio_handle.layout());
        this.stateBuffer = arena.allocate(ValueLayout.JAVA_BOOLEAN);

        final var cDevice = arena.allocateFrom(device);

        // Open GPIO for output: initialized to low (8N1 logic not applicable here)
        if (Periphery.gpio_open(handle, cDevice, line, GPIO_DIR_OUT) < 0) {
            final var error = Periphery.gpio_errmsg(handle).getString(0);
            throw new RuntimeException("Failed to open GPIO %s line %d: %s".formatted(device, line, error));
        }
        log.atDebug().log("LED initialized on {} line {}", device, line);
    }

    /**
     * Turn the LED on.
     */
    public void on() {
        setState(true);
    }

    /**
     * Turn the LED off.
     */
    public void off() {
        setState(false);
    }

    /**
     * Set the LED state.
     *
     * @param value True for high (on), false for low (off).
     */
    public void setState(final boolean value) {
        lock.lock();
        try {
            Periphery.gpio_write(handle, value);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the current state of the LED pin.
     *
     * @return True if on, false if off.
     */
    public boolean getState() {
        lock.lock();
        try {
            if (Periphery.gpio_read(handle, stateBuffer) < 0) {
                final var error = Periphery.gpio_errmsg(handle).getString(0);
                throw new RuntimeException("Failed to read GPIO: %s".formatted(error));
            }
            return stateBuffer.get(ValueLayout.JAVA_BOOLEAN, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Toggle the LED state.
     */
    public void toggle() {
        lock.lock();
        try {
            setState(!getState());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Close the native handle and release native memory.
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
