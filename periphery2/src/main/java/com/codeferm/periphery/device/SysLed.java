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
import org.periphery.led_handle;

/**
 * Thread-safe LED wrapper for Linux sysfs LEDs using FFM.
 * <p>
 * This class provides a high-level API for controlling system LEDs.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class SysLed implements AutoCloseable {

    /**
     * Reentrant lock for thread-safe hardware access.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Managed arena for native memory lifecycle.
     */
    private final Arena arena;

    /**
     * Native handle for the c-periphery LED object.
     */
    private final MemorySegment handle;

    /**
     * Pre-allocated buffer for integer values (brightness, max brightness).
     */
    private final MemorySegment intBuffer;

    /**
     * Pre-allocated buffer for boolean values (on/off state).
     */
    private final MemorySegment boolBuffer;

    /**
     * Pre-allocated buffer for the LED name string.
     */
    private final MemorySegment nameBuffer;

    /**
     * Initialize LED using FFM and jextract bindings.
     *
     * @param name LED name in sysfs (e.g., "led0" or "pwr_led").
     * @throws RuntimeException If the LED cannot be opened.
     */
    public SysLed(final String name) {
        this.arena = Arena.ofShared();
        this.handle = arena.allocate(led_handle.layout());
        this.intBuffer = arena.allocate(ValueLayout.JAVA_INT);
        this.boolBuffer = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        // Standard Linux LED name limit is usually small, 64 is safe
        this.nameBuffer = arena.allocate(64);
        final var cName = arena.allocateFrom(name);
        if (Periphery.led_open(handle, cName) < 0) {
            final var error = Periphery.led_errmsg(handle).getString(0);
            throw new RuntimeException("Failed to open LED %s: %s".formatted(name, error));
        }
        log.atDebug().log("LED {} initialized via FFM", name);
    }

    /**
     * Read LED brightness as boolean.
     *
     * @return True if brightness > 0, false otherwise.
     */
    public boolean read() {
        lock.lock();
        try {
            if (Periphery.led_read(handle, boolBuffer) < 0) {
                final var error = Periphery.led_errmsg(handle).getString(0);
                throw new RuntimeException("Failed to read LED state: %s".formatted(error));
            }
            return boolBuffer.get(ValueLayout.JAVA_BOOLEAN, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Set LED brightness to max or off.
     *
     * @param value True for max brightness, false for off.
     */
    public void write(final boolean value) {
        lock.lock();
        try {
            if (Periphery.led_write(handle, value) < 0) {
                final var error = Periphery.led_errmsg(handle).getString(0);
                throw new RuntimeException("Failed to write LED state: %s".formatted(error));
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get LED brightness as integer.
     *
     * @return Brightness value.
     */
    public int getBrightness() {
        lock.lock();
        try {
            if (Periphery.led_get_brightness(handle, intBuffer) < 0) {
                final var error = Periphery.led_errmsg(handle).getString(0);
                throw new RuntimeException("Failed to get LED brightness: %s".formatted(error));
            }
            return intBuffer.get(ValueLayout.JAVA_INT, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Set LED brightness as integer.
     *
     * @param brightness Brightness value.
     */
    public void setBrightness(final int brightness) {
        lock.lock();
        try {
            if (Periphery.led_set_brightness(handle, brightness) < 0) {
                final var error = Periphery.led_errmsg(handle).getString(0);
                throw new RuntimeException("Failed to set LED brightness: %s".formatted(error));
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get maximum brightness supported by this LED.
     *
     * @return Max brightness value.
     */
    public int getMaxBrightness() {
        lock.lock();
        try {
            if (Periphery.led_get_max_brightness(handle, intBuffer) < 0) {
                final var error = Periphery.led_errmsg(handle).getString(0);
                throw new RuntimeException("Failed to get LED max brightness: %s".formatted(error));
            }
            return intBuffer.get(ValueLayout.JAVA_INT, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the kernel name of the LED.
     * <p>
     * Utilizes a pre-allocated buffer for the native call.
     * </p>
     *
     * @return LED name string.
     */
    public String getName() {
        lock.lock();
        try {
            if (Periphery.led_name(handle, nameBuffer, nameBuffer.byteSize()) < 0) {
                final var error = Periphery.led_errmsg(handle).getString(0);
                throw new RuntimeException("Failed to get LED name: %s".formatted(error));
            }
            return nameBuffer.getString(0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a string representation of the LED properties.
     *
     * @return LED properties formatted as a String.
     */
    @Override
    public String toString() {
        lock.lock();
        try {
            final var buffer = arena.allocate(1024);
            Periphery.led_tostring(handle, buffer, buffer.byteSize());
            return buffer.getString(0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes the LED resource and releases all native memory.
     */
    @Override
    public void close() {
        lock.lock();
        try {
            try (arena) {
                if (handle.address() != 0) {
                    Periphery.led_close(handle);
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
