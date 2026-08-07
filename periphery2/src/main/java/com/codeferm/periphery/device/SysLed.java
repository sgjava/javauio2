/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.led_handle;

/**
 * Thread-safe LED wrapper for Linux sysfs LEDs using FFM.
 * <p>
 * This class provides a high-level API for controlling system LEDs, extending {@link AbstractDevice} to inherit unified lifecycle
 * control and managed resource tracking.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.2.0
 * @since 1.0.0
 */
@Slf4j
public class SysLed extends AbstractDevice {

    /**
     * Reentrant lock for thread-safe hardware access.
     */
    private final ReentrantLock lock = new ReentrantLock();

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
     * Pre-allocated buffer for the toString representation to avoid execution-time allocations.
     */
    private final MemorySegment toStringBuffer;

    /**
     * Tracks the initial hardware state on construction to safely restore it during teardown.
     */
    private final boolean originalValue;

    /**
     * Initialize LED using FFM and jextract bindings.
     *
     * @param name LED name in sysfs (e.g., "led0" or "pwr_led").
     * @throws RuntimeException If the LED cannot be opened.
     */
    public SysLed(final String name) {
        super(led_handle.layout());

        final var deviceArena = getArena();
        final var deviceHandle = getHandle();

        this.intBuffer = deviceArena.allocate(ValueLayout.JAVA_INT);
        this.boolBuffer = deviceArena.allocate(ValueLayout.JAVA_BOOLEAN);
        this.nameBuffer = deviceArena.allocate(64);
        this.toStringBuffer = deviceArena.allocate(1024);

        final var cName = deviceArena.allocateFrom(name);
        if (Periphery.led_open(deviceHandle, cName) < 0) {
            final var error = Periphery.led_errmsg(deviceHandle).getString(0);
            throw new RuntimeException("Failed to open LED %s: %s".formatted(name, error));
        }

        // Capture initial state immediately after opening for automated reset tracking
        if (Periphery.led_read(deviceHandle, boolBuffer) < 0) {
            final var error = Periphery.led_errmsg(deviceHandle).getString(0);
            throw new RuntimeException("Failed to read initial LED state: %s".formatted(error));
        }
        this.originalValue = boolBuffer.get(ValueLayout.JAVA_BOOLEAN, 0);

        log.atDebug().log("LED {} initialized via FFM. Initial state tracker: {}", name, originalValue ? "ON" : "OFF");
    }

    /**
     * Read LED brightness as boolean.
     *
     * @return True if brightness > 0, false otherwise.
     */
    public boolean read() {
        lock.lock();
        try {
            if (Periphery.led_read(getHandle(), boolBuffer) < 0) {
                final var error = Periphery.led_errmsg(getHandle()).getString(0);
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
            if (Periphery.led_write(getHandle(), value) < 0) {
                final var error = Periphery.led_errmsg(getHandle()).getString(0);
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
            if (Periphery.led_get_brightness(getHandle(), intBuffer) < 0) {
                final var error = Periphery.led_errmsg(getHandle()).getString(0);
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
            if (Periphery.led_set_brightness(getHandle(), brightness) < 0) {
                final var error = Periphery.led_errmsg(getHandle()).getString(0);
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
            if (Periphery.led_get_max_brightness(getHandle(), intBuffer) < 0) {
                final var error = Periphery.led_errmsg(getHandle()).getString(0);
                throw new RuntimeException("Failed to get LED max brightness: %s".formatted(error));
            }
            return intBuffer.get(ValueLayout.JAVA_INT, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the kernel name of the LED.
     *
     * @return LED name string.
     */
    public String getName() {
        lock.lock();
        try {
            if (Periphery.led_name(getHandle(), nameBuffer, (int) nameBuffer.byteSize()) < 0) {
                final var error = Periphery.led_errmsg(getHandle()).getString(0);
                throw new RuntimeException("Failed to get LED name: %s".formatted(error));
            }
            return nameBuffer.getString(0);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            Periphery.led_tostring(getHandle(), toStringBuffer, (int) toStringBuffer.byteSize());
            return toStringBuffer.getString(0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Releases the native unmanaged c-periphery LED handle resources.
     * <p>
     * Guarantees hardware state reset occurs on the shutdown thread before releasing pointers.
     * </p>
     */
    @Override
    protected void closeNative() {
        lock.lock();
        try {
            final var deviceHandle = getHandle();
            if (getArena().scope().isAlive() && deviceHandle != null && deviceHandle.address() != 0) {
                // Restore hardware state immediately prior to unmapping
                log.atDebug().log("Restoring LED state to {} via shutdown hook sequence", originalValue);
                Periphery.led_write(deviceHandle, originalValue);

                Periphery.led_close(deviceHandle);
                log.atDebug().log("Native LED resources closed cleanly.");
            }
        } finally {
            lock.unlock();
        }
    }
}
