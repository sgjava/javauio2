/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.locks.ReentrantLock;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;

/**
 * HC-SR04 Ultrasonic Distance sensor implementation using Foreign Function & Memory (FFM) API.
 * <p>
 * NOTE: The Echo pin (5V) must be connected to the Raspberry Pi GPIO (3.3V) using a voltage divider: 1k ohm resistor from Echo to
 * GPIO, and a 2k ohm resistor from GPIO to Ground.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class HcSr04 implements AutoCloseable {

    /**
     * Arena for managing native memory lifecycle.
     */
    private final Arena arena;

    /**
     * Native handle for the trigger GPIO line.
     */
    private final MemorySegment trigHandle;

    /**
     * Native handle for the echo GPIO line.
     */
    private final MemorySegment echoHandle;

    /**
     * Buffer for reading GPIO state.
     */
    private final MemorySegment stateBuffer;

    /**
     * Reentrant lock to ensure atomic sensor readings.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Most recently measured distance in centimeters.
     */
    @Getter
    private double distance;

    /**
     * Constructs an HC-SR04 interface.
     *
     * @param trigDevice Path to the GPIO chip for the trigger line.
     * @param trigLine GPIO line number for trigger.
     * @param echoDevice Path to the GPIO chip for the echo line.
     * @param echoLine GPIO line number for echo.
     */
    public HcSr04(final String trigDevice, final int trigLine, final String echoDevice, final int echoLine) {
        this.arena = Arena.ofShared();
        this.trigHandle = this.arena.allocate(gpio_handle.layout());
        this.echoHandle = this.arena.allocate(gpio_handle.layout());
        this.stateBuffer = this.arena.allocate(ValueLayout.JAVA_BOOLEAN);

        final var trigPath = this.arena.allocateFrom(trigDevice);
        final var echoPath = this.arena.allocateFrom(echoDevice);

        // Open trigger as OUT (1) and echo as IN (0)
        if (Periphery.gpio_open(this.trigHandle, trigPath, trigLine, 1) < 0) {
            throw new RuntimeException("Failed to open trigger GPIO line");
        }
        if (Periphery.gpio_open(this.echoHandle, echoPath, echoLine, 0) < 0) {
            throw new RuntimeException("Failed to open echo GPIO line");
        }
    }

    /**
     * Performs an ultrasonic distance measurement.
     *
     * @return True if the measurement was successful, false if a timeout occurred.
     */
    public boolean read() {
        this.lock.lock();
        try {
            // Trigger: 10us HIGH pulse
            Periphery.gpio_write(this.trigHandle, true);
            busyWait(10_000L);
            Periphery.gpio_write(this.trigHandle, false);

            // Timeout after 30ms (max range for HC-SR04 is ~400cm)
            final var timeout = System.nanoTime() + 30_000_000L;

            // Wait for echo to go high
            while (!readEcho()) {
                if (System.nanoTime() > timeout) {
                    return false;
                }
                Thread.onSpinWait();
            }
            final var start = System.nanoTime();

            // Wait for echo to go low
            while (readEcho()) {
                if (System.nanoTime() > timeout) {
                    return false;
                }
                Thread.onSpinWait();
            }
            final var end = System.nanoTime();

            // Distance in cm = (duration_ns / 1000.0) / 58.0
            this.distance = ((end - start) / 1000.0) / 58.0;
            return true;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Reads the current state of the echo GPIO line.
     *
     * @return True if high, false if low.
     */
    private boolean readEcho() {
        Periphery.gpio_read(this.echoHandle, this.stateBuffer);
        return this.stateBuffer.get(ValueLayout.JAVA_BOOLEAN, 0);
    }

    /**
     * Performs a high-precision busy-wait.
     *
     * @param ns Nanoseconds to wait.
     */
    private void busyWait(final long ns) {
        final var start = System.nanoTime();
        while (System.nanoTime() - start < ns) {
            Thread.onSpinWait();
        }
    }

    /**
     * Releases native GPIO resources and closes the memory arena.
     */
    @Override
    public void close() {
        this.lock.lock();
        try {
            try (this.arena) {
                Periphery.gpio_close(this.trigHandle);
                Periphery.gpio_close(this.echoHandle);
            }
        } finally {
            this.lock.unlock();
        }
    }
}
