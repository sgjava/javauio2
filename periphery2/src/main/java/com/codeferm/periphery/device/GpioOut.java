/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;

/**
 * Generic GPIO output device (LED, Active Buzzer, Relay, etc.) using pure FFM bindings.
 * <p>
 * High-performance, thread-safe control of a GPIO line. This implementation leverages the {@link AbstractDevice} base class to
 * ensure zero-allocation during operation and deterministic resource cleanup.
 * </p>
 * <p>
 * Extends {@link AbstractDevice} to delegate native plumbing and memory lifecycle management.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class GpioOut extends AbstractDevice {

    /**
     * GPIO direction out constant from c-periphery.
     */
    private static final int GPIO_DIR_OUT = 1;

    /**
     * Lock for thread-safe hardware access.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Pre-allocated buffer for state reading to avoid GC pressure.
     */
    private final MemorySegment stateBuffer;

    /**
     * Constructor for GpioOut.
     *
     * @param device GPIO device path (e.g., "/dev/gpiochip0").
     * @param line GPIO line number.
     * @throws RuntimeException if the GPIO cannot be opened.
     */
    public GpioOut(final String device, final int line) {
        super(gpio_handle.layout());

        // Allocate state buffer using the inherited arena
        this.stateBuffer = getArena().allocate(ValueLayout.JAVA_BOOLEAN);
        final var cDevice = getArena().allocateFrom(device);

        checkError(Periphery.gpio_open(getHandle(), cDevice, line, GPIO_DIR_OUT),
                String.format("Failed to open GPIO %s line %d", device, line));

        log.atDebug().log("GPIO output initialized on {} line {}", device, line);
    }

    /**
     * Set the output state to high (on).
     */
    public void on() {
        setState(true);
    }

    /**
     * Set the output state to low (off).
     */
    public void off() {
        setState(false);
    }

    /**
     * Set the GPIO state.
     *
     * @param value True for high (on), false for low (off).
     */
    public void setState(final boolean value) {
        lock.lock();
        try {
            Periphery.gpio_write(getHandle(), value);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the current state of the GPIO pin.
     *
     * @return True if high, false if low.
     */
    public boolean getState() {
        lock.lock();
        try {
            checkError(Periphery.gpio_read(getHandle(), stateBuffer), "Failed to read GPIO");
            return stateBuffer.get(ValueLayout.JAVA_BOOLEAN, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Toggle the current GPIO state.
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
     * Releases native resources via the periphery library.
     */
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
}
