/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;

/**
 * XPT2046 touch controller implementation using c-periphery FFM bindings with IRQ edge polling.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class Xpt2046 extends AbstractTouch {

    private final String spiDevice;
    private final int spiMode;
    private final int spiSpeed;
    private final String gpioDevice;
    private final int pinIrq;

    private final MemorySegment spiHandle;
    private final MemorySegment irqHandle;

    private Arena arena;
    private MemorySegment txSegment;
    private MemorySegment rxSegment;
    private MemorySegment valueSegment;
    private MemorySegment eventBuffer;
    private MemorySegment edgePtr;
    private MemorySegment timestampPtr;

    private static final byte CMD_READ_Y = (byte) 0x90; // 10010000 (Channel Y)
    private static final byte CMD_READ_X = (byte) 0xD0; // 11010000 (Channel X)
    private static final int GPIO_DIR_IN = 0;

    /**
     * Construct XPT2046 touch controller.
     *
     * @param spiDevice SPI device path (e.g., "/dev/spidev1.0").
     * @param spiMode SPI mode.
     * @param spiSpeed SPI max speed in Hz.
     * @param gpioDevice GPIO device path (e.g., "/dev/gpiochip0").
     * @param pinIrq GPIO line offset for IRQ.
     */
    public Xpt2046(final String spiDevice, final int spiMode, final int spiSpeed,
            final String gpioDevice, final int pinIrq) {
        this.spiDevice = spiDevice;
        this.spiMode = spiMode;
        this.spiSpeed = spiSpeed;
        this.gpioDevice = gpioDevice;
        this.pinIrq = pinIrq;
        this.spiHandle = Periphery.spi_new();
        this.irqHandle = Periphery.gpio_new();
    }

    @Override
    public void open() {
        if (spiHandle.address() == 0 || irqHandle.address() == 0) {
            throw new RuntimeException("Failed to allocate native SPI or GPIO handles for XPT2046");
        }

        this.arena = Arena.ofConfined();
        this.txSegment = arena.allocate(3);
        this.rxSegment = arena.allocate(3);
        this.valueSegment = arena.allocate(1);

        // Pre-allocate event buffer structures securely for zero-allocation loop handling
        this.eventBuffer = arena.allocate(16, 8);
        this.edgePtr = eventBuffer.asSlice(0, ValueLayout.JAVA_INT.byteSize());
        this.timestampPtr = eventBuffer.asSlice(8, ValueLayout.JAVA_LONG.byteSize());

        final var cDevice = arena.allocateFrom(spiDevice);
        final var cGpioDev = arena.allocateFrom(gpioDevice);

        if (Periphery.spi_open(spiHandle, cDevice, spiMode, spiSpeed) < 0) {
            throw new RuntimeException("SPI open failed for touch: " + spiDevice);
        }
        if (Periphery.gpio_open(irqHandle, cGpioDev, pinIrq, GPIO_DIR_IN) < 0) {
            final var errSegment = Periphery.gpio_errmsg(irqHandle);
            final var errMsg = errSegment != null ? errSegment.getString(0L) : "Unknown error";
            throw new RuntimeException("Touch IRQ GPIO open failed: " + errMsg);
        }

        // Configure edge detection for both falling and rising edges
        Periphery.gpio_set_edge(irqHandle, Periphery.GPIO_EDGE_BOTH());

        log.info("XPT2046 opened successfully on {} with IRQ line {}", spiDevice, pinIrq);
    }

    @Override
    public boolean isPressed() {
        if (Periphery.gpio_read(irqHandle, valueSegment) == 0) {
            // XPT2046 IRQ pin goes low when pressed
            return !valueSegment.get(ValueLayout.JAVA_BOOLEAN, 0L);
        }
        return false;
    }

    @Override
    public TouchPoint readCoordinates() {
        final var rawX = readCoordinate(CMD_READ_X);
        final var rawY = readCoordinate(CMD_READ_Y);
        return new TouchPoint(rawX, rawY);
    }

    /**
     * Poll for a touch IRQ edge event with timeout.
     *
     * @param timeoutMs Timeout in milliseconds.
     * @return True if an edge event occurred, false otherwise.
     */
    public boolean pollEvent(final int timeoutMs) {
        final var ret = Periphery.gpio_poll(irqHandle, timeoutMs);
        if (ret > 0) {
            return Periphery.gpio_read_event(irqHandle, edgePtr, timestampPtr) >= 0;
        }
        return false;
    }

    /**
     * Get the last read edge type (Falling = touch down, Rising = touch up).
     *
     * @return Edge constant.
     */
    public int getEdge() {
        return edgePtr.get(ValueLayout.JAVA_INT, 0);
    }

    /**
     * Read a 12-bit raw coordinate from the XPT2046 controller via SPI.
     *
     * @param command Command byte (e.g., CMD_READ_X or CMD_READ_Y).
     * @return 12-bit integer measurement value.
     */
    private int readCoordinate(final byte command) {
        txSegment.set(ValueLayout.JAVA_BYTE, 0L, command);
        txSegment.set(ValueLayout.JAVA_BYTE, 1L, (byte) 0x00);
        txSegment.set(ValueLayout.JAVA_BYTE, 2L, (byte) 0x00);

        if (Periphery.spi_transfer(spiHandle, txSegment, rxSegment, 3) < 0) {
            throw new RuntimeException("SPI touch transfer failed");
        }

        final var high = rxSegment.get(ValueLayout.JAVA_BYTE, 1L) & 0xFF;
        final var low = rxSegment.get(ValueLayout.JAVA_BYTE, 2L) & 0xFF;

        // XPT2046 returns 12-bit result shifted right by 4 bits
        return ((high << 8) | low) >> 4;
    }

    @Override
    public void close() {
        try {
            Periphery.spi_close(spiHandle);
            Periphery.gpio_close(irqHandle);
        } finally {
            Periphery.spi_free(spiHandle);
            Periphery.gpio_free(irqHandle);
            if (arena != null) {
                arena.close();
            }
            log.info("XPT2046 closed and native resources released.");
        }
    }
}
