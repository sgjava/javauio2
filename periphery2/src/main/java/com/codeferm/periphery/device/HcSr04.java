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
 * HC-SR04 Ultrasonic Distance sensor implementation using FFM API.
 * <p>
 * This implementation utilizes high-precision busy-waiting and {@link Thread#onSpinWait()} to capture the ultrasonic pulse timing
 * with microsecond accuracy.
 * </p>
 * <p>
 * NOTE: The Echo pin (5V) must be connected to the Raspberry Pi GPIO (3.3V) using a voltage divider.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class HcSr04 extends AbstractDevice {

    private final MemorySegment echoHandle;
    private final MemorySegment stateBuffer;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Most recently measured distance in centimeters.
     */
    @Getter
    private double distance;

    /**
     * Constructs an HC-SR04 interface.
     *
     * @param trigDevice Path to the trigger GPIO chip.
     * @param trigLine GPIO line for trigger.
     * @param echoDevice Path to the echo GPIO chip.
     * @param echoLine GPIO line for echo.
     */
    public HcSr04(final String trigDevice, final int trigLine,
            final String echoDevice, final int echoLine) {
        super(gpio_handle.layout());

        // trigHandle is managed by super.getHandle()
        this.echoHandle = getArena().allocate(gpio_handle.layout());
        this.stateBuffer = getArena().allocate(ValueLayout.JAVA_BOOLEAN);

        final var trigPath = getArena().allocateFrom(trigDevice);
        final var echoPath = getArena().allocateFrom(echoDevice);

        // Open trigger as OUT (1)
        checkError(Periphery.gpio_open(getHandle(), trigPath, trigLine, 1),
                "Failed to open HC-SR04 Trigger line");

        // Open echo as IN (0)
        checkError(Periphery.gpio_open(echoHandle, echoPath, echoLine, 0),
                "Failed to open HC-SR04 Echo line");

        log.atDebug().log("HC-SR04 initialized (Trig: {}, Echo: {})", trigLine, echoLine);
    }

    /**
     * Performs an ultrasonic distance measurement.
     *
     * @return True if measurement was successful, false on timeout.
     */
    public boolean read() {
        lock.lock();
        try {
            // Trigger: 10us HIGH pulse
            Periphery.gpio_write(getHandle(), true);
            busyWait(10_000L);
            Periphery.gpio_write(getHandle(), false);

            // Timeout after 30ms (max range ~400cm)
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
            lock.unlock();
        }
    }

    /**
     * Reads the current state of the echo GPIO line.
     *
     * @return True if high, false if low.
     */
    private boolean readEcho() {
        Periphery.gpio_read(echoHandle, stateBuffer);
        return stateBuffer.get(ValueLayout.JAVA_BOOLEAN, 0);
    }

    /**
     * Performs a high-precision busy-wait using hint for the processor.
     *
     * @param ns Nanoseconds to wait.
     */
    private void busyWait(final long ns) {
        final var start = System.nanoTime();
        while (System.nanoTime() - start < ns) {
            Thread.onSpinWait();
        }
    }

    @Override
    protected void closeNative() {
        lock.lock();
        try {
            if (getHandle().address() != 0) {
                Periphery.gpio_close(getHandle());
            }
            if (echoHandle.address() != 0) {
                Periphery.gpio_close(echoHandle);
            }
        } finally {
            lock.unlock();
        }
    }
}
