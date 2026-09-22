/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;

/**
 * GC9A01 240x240 round SPI IPS LCD module driver using Java Foreign Function & Memory (FFM) API, extending
 * {@link AbstractColorDisplay}.
 * <p>
 * This driver provides a high-performance interface to the GC9A01 controller, utilizing {@link MemorySegment} for zero-copy data
 * transfers. Optimized with a zero-allocation strategy to prevent memory thrashing and OutOfMemoryErrors in tight loops. Supports
 * required hardware Reset (RST), optional Backlight GPIO or PWM backlight brightness control, display rotation, and configuration
 * via raw byte array scripts.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.11
 * @since 1.0.0
 */
@Slf4j
public class Gc9a01 extends AbstractColorDisplay {

    /**
     * Sleep In command.
     */
    public static final byte SLPIN = (byte) 0x10;

    /**
     * Normal Display Mode On.
     */
    public static final byte NORON = (byte) 0x13;

    /**
     * Display Off.
     */
    public static final byte DISPOFF = (byte) 0x28;

    /**
     * Display On.
     */
    public static final byte DISPON = (byte) 0x29;

    /**
     * Column Address Set.
     */
    public static final byte CASET = (byte) 0x2A;

    /**
     * Row Address Set.
     */
    public static final byte RASET = (byte) 0x2B;

    /**
     * Memory Write.
     */
    public static final byte RAMWR = (byte) 0x2C;

    /**
     * Memory Access Control.
     */
    public static final byte MADCTL = (byte) 0x36;

    /**
     * Interface Pixel Format.
     */
    public static final byte COLMOD = (byte) 0x3A;

    /**
     * Byte array script opcode definitions.
     */
    private static final byte OP_END = 0x00;
    private static final byte OP_CMD = 0x01;
    private static final byte OP_DATA = 0x02;
    private static final byte OP_DELAY = 0x03;

    /**
     * Raw byte array configuration script for GC9A01.
     */
    private static final byte[] GC9A01_CFG_SCRIPT = {
        OP_CMD, (byte) 0xEF,
        OP_CMD, (byte) 0xEB,
        OP_DATA, (byte) 0x14,
        OP_CMD, (byte) 0xFE,
        OP_CMD, (byte) 0xEF,
        OP_CMD, (byte) 0xEB,
        OP_DATA, (byte) 0x14,
        OP_CMD, (byte) 0x84,
        OP_DATA, (byte) 0x40,
        OP_CMD, (byte) 0x85,
        OP_DATA, (byte) 0xFF,
        OP_CMD, (byte) 0x86,
        OP_DATA, (byte) 0xFF,
        OP_CMD, (byte) 0x87,
        OP_DATA, (byte) 0xFF,
        OP_CMD, (byte) 0x88,
        OP_DATA, (byte) 0x0A,
        OP_CMD, (byte) 0x89,
        OP_DATA, (byte) 0x21,
        OP_CMD, (byte) 0x8A,
        OP_DATA, (byte) 0x00,
        OP_CMD, (byte) 0x8B,
        OP_DATA, (byte) 0x80,
        OP_CMD, (byte) 0x8C,
        OP_DATA, (byte) 0x01,
        OP_CMD, (byte) 0x8D,
        OP_DATA, (byte) 0x01,
        OP_CMD, (byte) 0x8E,
        OP_DATA, (byte) 0xFF,
        OP_CMD, (byte) 0x8F,
        OP_DATA, (byte) 0xFF,
        OP_CMD, (byte) 0xB6,
        OP_DATA, (byte) 0x00,
        OP_DATA, (byte) 0x00,
        OP_CMD, (byte) 0x36,
        OP_DATA, (byte) 0x48,
        OP_CMD, (byte) 0x3A,
        OP_DATA, (byte) 0x05,
        OP_CMD, (byte) 0x90,
        OP_DATA, (byte) 0x08,
        OP_DATA, (byte) 0x08,
        OP_DATA, (byte) 0x08,
        OP_DATA, (byte) 0x08,
        OP_CMD, (byte) 0xBD,
        OP_DATA, (byte) 0x06,
        OP_CMD, (byte) 0xBC,
        OP_DATA, (byte) 0x00,
        OP_CMD, (byte) 0xFF,
        OP_DATA, (byte) 0x60,
        OP_DATA, (byte) 0x01,
        OP_DATA, (byte) 0x04,
        OP_CMD, (byte) 0xC3,
        OP_DATA, (byte) 0x13,
        OP_CMD, (byte) 0xC4,
        OP_DATA, (byte) 0x13,
        OP_CMD, (byte) 0xC9,
        OP_DATA, (byte) 0x22,
        OP_CMD, (byte) 0xBE,
        OP_DATA, (byte) 0x11,
        OP_CMD, (byte) 0xE1,
        OP_DATA, (byte) 0x10,
        OP_DATA, (byte) 0x0E,
        OP_CMD, (byte) 0xDF,
        OP_DATA, (byte) 0x21,
        OP_DATA, (byte) 0x0C,
        OP_DATA, (byte) 0x02,
        OP_CMD, (byte) 0xF0,
        OP_DATA, (byte) 0x45,
        OP_DATA, (byte) 0x09,
        OP_DATA, (byte) 0x08,
        OP_DATA, (byte) 0x08,
        OP_DATA, (byte) 0x26,
        OP_DATA, (byte) 0x2A,
        OP_CMD, (byte) 0xF1,
        OP_DATA, (byte) 0x43,
        OP_DATA, (byte) 0x70,
        OP_DATA, (byte) 0x72,
        OP_DATA, (byte) 0x36,
        OP_DATA, (byte) 0x37,
        OP_DATA, (byte) 0x6F,
        OP_CMD, (byte) 0xF2,
        OP_DATA, (byte) 0x45,
        OP_DATA, (byte) 0x09,
        OP_DATA, (byte) 0x08,
        OP_DATA, (byte) 0x08,
        OP_DATA, (byte) 0x26,
        OP_DATA, (byte) 0x2A,
        OP_CMD, (byte) 0xF3,
        OP_DATA, (byte) 0x43,
        OP_DATA, (byte) 0x70,
        OP_DATA, (byte) 0x72,
        OP_DATA, (byte) 0x36,
        OP_DATA, (byte) 0x37,
        OP_DATA, (byte) 0x6F,
        OP_CMD, (byte) 0xED,
        OP_DATA, (byte) 0x1B,
        OP_DATA, (byte) 0x0B,
        OP_CMD, (byte) 0xAE,
        OP_DATA, (byte) 0x77,
        OP_CMD, (byte) 0xCD,
        OP_DATA, (byte) 0x63,
        OP_CMD, (byte) 0x70,
        OP_DATA, (byte) 0x07,
        OP_DATA, (byte) 0x07,
        OP_DATA, (byte) 0x04,
        OP_DATA, (byte) 0x0E,
        OP_DATA, (byte) 0x0F,
        OP_DATA, (byte) 0x09,
        OP_DATA, (byte) 0x07,
        OP_DATA, (byte) 0x08,
        OP_DATA, (byte) 0x03,
        OP_CMD, (byte) 0xE8,
        OP_DATA, (byte) 0x34,
        OP_CMD, (byte) 0x62,
        OP_DATA, (byte) 0x18,
        OP_DATA, (byte) 0x0D,
        OP_DATA, (byte) 0x71,
        OP_DATA, (byte) 0xED,
        OP_DATA, (byte) 0x70,
        OP_DATA, (byte) 0x70,
        OP_DATA, (byte) 0x18,
        OP_DATA, (byte) 0x0F,
        OP_DATA, (byte) 0x71,
        OP_DATA, (byte) 0xEF,
        OP_DATA, (byte) 0x70,
        OP_DATA, (byte) 0x70,
        OP_CMD, (byte) 0x63,
        OP_DATA, (byte) 0x18,
        OP_DATA, (byte) 0x11,
        OP_DATA, (byte) 0x71,
        OP_DATA, (byte) 0xF1,
        OP_DATA, (byte) 0x70,
        OP_DATA, (byte) 0x70,
        OP_DATA, (byte) 0x18,
        OP_DATA, (byte) 0x13,
        OP_DATA, (byte) 0x71,
        OP_DATA, (byte) 0xF3,
        OP_DATA, (byte) 0x70,
        OP_DATA, (byte) 0x70,
        OP_CMD, (byte) 0x64,
        OP_DATA, (byte) 0x28,
        OP_DATA, (byte) 0x29,
        OP_DATA, (byte) 0xF1,
        OP_DATA, (byte) 0x01,
        OP_DATA, (byte) 0xF1,
        OP_DATA, (byte) 0x00,
        OP_DATA, (byte) 0x07,
        OP_CMD, (byte) 0x66,
        OP_DATA, (byte) 0x3C,
        OP_DATA, (byte) 0x00,
        OP_DATA, (byte) 0xCD,
        OP_DATA, (byte) 0x67,
        OP_DATA, (byte) 0x45,
        OP_DATA, (byte) 0x45,
        OP_DATA, (byte) 0x10,
        OP_DATA, (byte) 0x00,
        OP_DATA, (byte) 0x00,
        OP_DATA, (byte) 0x00,
        OP_CMD, (byte) 0x67,
        OP_DATA, (byte) 0x00,
        OP_DATA, (byte) 0x3C,
        OP_DATA, (byte) 0x00,
        OP_DATA, (byte) 0x00,
        OP_DATA, (byte) 0x00,
        OP_DATA, (byte) 0x01,
        OP_DATA, (byte) 0x54,
        OP_DATA, (byte) 0x10,
        OP_DATA, (byte) 0x32,
        OP_DATA, (byte) 0x98,
        OP_CMD, (byte) 0x74,
        OP_DATA, (byte) 0x10,
        OP_DATA, (byte) 0x85,
        OP_DATA, (byte) 0x80,
        OP_DATA, (byte) 0x00,
        OP_DATA, (byte) 0x00,
        OP_DATA, (byte) 0x4E,
        OP_DATA, (byte) 0x00,
        OP_CMD, (byte) 0x98,
        OP_DATA, (byte) 0x3E,
        OP_DATA, (byte) 0x07,
        OP_CMD, (byte) 0x35,
        OP_CMD, (byte) 0x21,
        OP_CMD, (byte) 0x11,
        OP_DELAY, (byte) 120,
        OP_CMD, (byte) 0x29,
        OP_DELAY, (byte) (byte) 255,
        OP_END
    };

    /**
     * Reset GPIO handle.
     */
    @Getter
    private final MemorySegment rstHandle;

    /**
     * Backlight LED GPIO handle (used if PWM is not configured).
     */
    @Getter
    private final MemorySegment ledHandle;

    /**
     * Optional PWM backlight controller wrapper.
     */
    private final PwmBacklight pwmBacklight;

    /**
     * Initializes hardware with SPI, DC pin, and Reset pin (no Backlight or PWM) using default buffer size.
     *
     * @param device SPI device path.
     * @param mode SPI mode.
     * @param speed SPI speed in Hz.
     * @param gpioDevice GPIO chip path.
     * @param dcPin Data/Command BCM pin number.
     * @param rstPin Reset BCM pin number.
     */
    public Gc9a01(final String device, final int mode, final int speed, final String gpioDevice, final int dcPin,
            final int rstPin) {
        this(device, mode, speed, gpioDevice, dcPin, rstPin, -1, 65536);
    }

    /**
     * Initializes hardware with SPI, DC pin, Reset pin, and Backlight GPIO pin using default buffer size.
     *
     * @param device SPI device path.
     * @param mode SPI mode.
     * @param speed SPI speed in Hz.
     * @param gpioDevice GPIO chip path.
     * @param dcPin Data/Command BCM pin number.
     * @param rstPin Reset BCM pin number.
     * @param ledPin Backlight BCM pin number (or -1 if unused).
     */
    public Gc9a01(final String device, final int mode, final int speed, final String gpioDevice, final int dcPin,
            final int rstPin, final int ledPin) {
        this(device, mode, speed, gpioDevice, dcPin, rstPin, ledPin, 65536);
    }

    /**
     * Initializes hardware with SPI, DC pin, Reset pin, and Backlight GPIO pin with a configurable buffer size.
     *
     * @param device SPI device path.
     * @param mode SPI mode.
     * @param speed SPI speed in Hz.
     * @param gpioDevice GPIO chip path.
     * @param dcPin Data/Command BCM pin number.
     * @param rstPin Reset BCM pin number.
     * @param ledPin Backlight BCM pin number (or -1 if unused).
     * @param bufferSize Transfer buffer chunk size in bytes.
     */
    public Gc9a01(final String device, final int mode, final int speed, final String gpioDevice, final int dcPin,
            final int rstPin, final int ledPin, final int bufferSize) {
        super(240, 240, bufferSize);
        this.rstHandle = Periphery.gpio_new();
        this.ledHandle = ledPin >= 0 ? Periphery.gpio_new() : MemorySegment.NULL;
        this.pwmBacklight = null;

        if (rstHandle.address() == 0) {
            throw new RuntimeException("Failed to allocate native Reset GPIO handle");
        }
        if (ledPin >= 0 && ledHandle.address() == 0) {
            throw new RuntimeException("Failed to allocate native LED Backlight GPIO handle");
        }

        final var cDevice = getArena().allocateFrom(device);
        final var cGpioDev = getArena().allocateFrom(gpioDevice);
        if (Periphery.spi_open(getHandle(), cDevice, mode, speed) < 0) {
            throw new RuntimeException("SPI open failed");
        }
        if (Periphery.gpio_open(getDcHandle(), cGpioDev, dcPin, GPIO_DIR_OUT) < 0) {
            throw new RuntimeException("DC GPIO open failed");
        }
        if (Periphery.gpio_open(rstHandle, cGpioDev, rstPin, GPIO_DIR_OUT) < 0) {
            throw new RuntimeException("RST GPIO open failed");
        }
        if (ledPin >= 0) {
            if (Periphery.gpio_open(ledHandle, cGpioDev, ledPin, GPIO_DIR_OUT) < 0) {
                throw new RuntimeException("LED Backlight GPIO open failed");
            }
        }
        setup();
    }

    /**
     * Initializes hardware with SPI, DC, Reset handles, and a unified {@link PwmBacklight} via FFM using a default 64KB chunk
     * buffer.
     *
     * @param device SPI device path.
     * @param mode SPI mode.
     * @param speed SPI speed in Hz.
     * @param gpioDevice GPIO chip path.
     * @param dcPin Data/Command pin number.
     * @param rstPin Reset pin number.
     * @param pwmBacklight Configured PWM backlight controller instance.
     */
    public Gc9a01(final String device, final int mode, final int speed, final String gpioDevice, final int dcPin,
            final int rstPin, final PwmBacklight pwmBacklight) {
        this(device, mode, speed, gpioDevice, dcPin, rstPin, pwmBacklight, 65536);
    }

    /**
     * Initializes hardware with SPI, DC, Reset handles, and a unified {@link PwmBacklight} via FFM with a configurable buffer size.
     *
     * @param device SPI device path.
     * @param mode SPI mode.
     * @param speed SPI speed in Hz.
     * @param gpioDevice GPIO chip path.
     * @param dcPin Data/Command pin number.
     * @param rstPin Reset pin number.
     * @param pwmBacklight Configured PWM backlight controller instance.
     * @param bufferSize Transfer buffer chunk size in bytes.
     */
    public Gc9a01(final String device, final int mode, final int speed, final String gpioDevice, final int dcPin,
            final int rstPin, final PwmBacklight pwmBacklight, final int bufferSize) {
        super(240, 240, bufferSize);
        this.rstHandle = Periphery.gpio_new();
        this.ledHandle = MemorySegment.NULL;
        this.pwmBacklight = pwmBacklight;

        if (rstHandle.address() == 0) {
            throw new RuntimeException("Failed to allocate native Reset GPIO handle");
        }

        final var cDevice = getArena().allocateFrom(device);
        final var cGpioDev = getArena().allocateFrom(gpioDevice);
        if (Periphery.spi_open(getHandle(), cDevice, mode, speed) < 0) {
            throw new RuntimeException("SPI open failed");
        }
        if (Periphery.gpio_open(getDcHandle(), cGpioDev, dcPin, GPIO_DIR_OUT) < 0) {
            throw new RuntimeException("DC GPIO open failed");
        }
        if (Periphery.gpio_open(rstHandle, cGpioDev, rstPin, GPIO_DIR_OUT) < 0) {
            throw new RuntimeException("RST GPIO open failed");
        }
        setup();
    }

    /**
     * Sets the display rotation orientation (0, 90, 180, 270 degrees) and updates the hardware memory access control (MADCTL)
     * register.
     *
     * @param rotation Rotation angle in degrees.
     */
    @Override
    public final void setRotation(final int rotation) {
        super.setRotation(rotation);
        final var mode = (this.rotation / 90) % 4;
        byte madctlValue;
        switch (mode) {
            case 1:
                madctlValue = (byte) 0x68; // 90 degrees
                break;
            case 2:
                madctlValue = (byte) 0xC8; // 180 degrees
                break;
            case 3:
                madctlValue = (byte) 0xA8; // 270 degrees
                break;
            case 0:
            default:
                madctlValue = (byte) 0x48; // 0 degrees
                break;
        }
        if (getHandle().address() != 0 && getArena().scope().isAlive()) {
            writeCommand(new byte[]{MADCTL, madctlValue});
        }
    }

    /**
     * Sends command bytes and optional parameters, correctly managing D/C line state transitions.
     *
     * @param data Command array with optional parameter bytes.
     */
    @Override
    public final void writeCommand(final byte[] data) {
        if (getHandle().address() != 0 && getArena().scope().isAlive()) {
            Periphery.gpio_write(getDcHandle(), false);
            getCommandSegment().set(ValueLayout.JAVA_BYTE, 0L, data[0]);
            if (Periphery.spi_transfer(getHandle(), getCommandSegment(), MemorySegment.NULL, 1) < 0) {
                throw new RuntimeException("SPI Command failed");
            }
            if (data.length > 1) {
                Periphery.gpio_write(getDcHandle(), true);
                MemorySegment.copy(data, 1, getCommandSegment(), ValueLayout.JAVA_BYTE, 0, data.length - 1);
                if (Periphery.spi_transfer(getHandle(), getCommandSegment(), MemorySegment.NULL, data.length - 1) < 0) {
                    throw new RuntimeException("SPI Command parameters failed");
                }
            }
        }
    }

    /**
     * Sends data bytes (pixels) directly from a {@link MemorySegment} in optimized chunks.
     *
     * @param segment Native segment containing pixel data.
     */
    @Override
    public final void writeData(final MemorySegment segment) {
        if (getHandle().address() != 0 && getArena().scope().isAlive()) {
            Periphery.gpio_write(getDcHandle(), true);
            final var totalBytes = segment.byteSize();
            var offset = 0L;
            while (offset < totalBytes) {
                final var length = (int) Math.min(bufferSize, totalBytes - offset);
                final var chunk = segment.asSlice(offset, length);
                if (Periphery.spi_transfer(getHandle(), chunk, MemorySegment.NULL, length) < 0) {
                    throw new RuntimeException("SPI Data Segment transfer failed at offset " + offset);
                }
                offset += length;
            }
        }
    }

    /**
     * Performs hardware reset, backlight activation, and complete initialization script execution for GC9A01 round IPS panels.
     */
    public final void setup() {
        try {
            if (pwmBacklight != null) {
                pwmBacklight.enable();
                pwmBacklight.setBrightness(1_000_000L, 1.0);
            } else if (ledHandle.address() != 0) {
                Periphery.gpio_write(ledHandle, true);
            }

            Periphery.gpio_write(rstHandle, true);
            TimeUnit.MILLISECONDS.sleep(50);
            Periphery.gpio_write(rstHandle, false);
            TimeUnit.MILLISECONDS.sleep(50);
            Periphery.gpio_write(rstHandle, true);
            TimeUnit.MILLISECONDS.sleep(50);

            // Run configuration byte script
            int i = 0;
            while (i < GC9A01_CFG_SCRIPT.length) {
                final var op = GC9A01_CFG_SCRIPT[i++];
                if (op == OP_END) {
                    break;
                }
                switch (op) {
                    case OP_CMD -> {
                        final var cmd = GC9A01_CFG_SCRIPT[i++] & 0xFF;
                        writeCommand(new byte[]{(byte) cmd});
                    }
                    case OP_DATA -> {
                        final var data = GC9A01_CFG_SCRIPT[i++] & 0xFF;
                        Periphery.gpio_write(getDcHandle(), true);
                        getCommandSegment().set(ValueLayout.JAVA_BYTE, 0L, (byte) data);
                        if (Periphery.spi_transfer(getHandle(), getCommandSegment(), MemorySegment.NULL, 1) < 0) {
                            throw new RuntimeException("SPI config data transfer failed: 0x" + Integer.toHexString(data));
                        }
                    }
                    case OP_DELAY -> {
                        final var delay = GC9A01_CFG_SCRIPT[i++] & 0xFF;
                        TimeUnit.MILLISECONDS.sleep(delay);
                    }
                    default ->
                        throw new IllegalArgumentException("Unknown script opcode: " + op);
                }
            }

            setRotation(rotation);
            clear();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Clears the display matching the exact frame boundaries.
     */
    @Override
    public final void clear() {
        writeCommand(new byte[]{CASET, (byte) 0x00, (byte) 0x00, (byte) ((getWidth() - 1) >> 8), (byte) (getWidth() - 1)});
        writeCommand(new byte[]{RASET, (byte) 0x00, (byte) 0x00, (byte) ((getHeight() - 1) >> 8), (byte) (getHeight() - 1)});
        writeCommand(new byte[]{RAMWR});
        getImageSegment().fill((byte) 0);
        writeData(getImageSegment());
    }

    /**
     * Draws a single pixel directly into the native frame buffer memory segment in RGB565 format.
     *
     * @param x X coordinate.
     * @param y Y coordinate.
     * @param color RGB color integer.
     */
    @Override
    public final void drawPixel(final int x, final int y, final int color) {
        if (x >= 0 && x < getWidth() && y >= 0 && y < getHeight()) {
            final var p = color;
            final var packed = (short) ((((p >> 19) & 0x1F) << 11) | (((p >> 10) & 0x3F) << 5) | ((p >> 3) & 0x1F));
            final var offset = (long) (y * getWidth() + x) * 2L;
            getImageSegment().set(ValueLayout.JAVA_SHORT_UNALIGNED, offset, Short.reverseBytes(packed));

            setWindow(x, y, 1, 1);
            final var pixelSegment = getImageSegment().asSlice(offset, 2L);
            writeData(pixelSegment);
        }
    }

    /**
     * Maps a {@link BufferedImage} to RGB565 and renders via pre-allocated segment.
     *
     * @param image BufferedImage to render.
     */
    @Override
    public final void drawImage(final BufferedImage image) {
        writeCommand(new byte[]{CASET, (byte) 0x00, (byte) 0x00, (byte) ((getWidth() - 1) >> 8), (byte) (getWidth() - 1)});
        writeCommand(new byte[]{RASET, (byte) 0x00, (byte) 0x00, (byte) ((getHeight() - 1) >> 8), (byte) (getHeight() - 1)});
        writeCommand(new byte[]{RAMWR});

        packRgb888ToRgb565(image);

        writeData(getImageSegment());
    }

    /**
     * Maps a sub-region of a {@link BufferedImage} to RGB565 and renders it to a specific window.
     *
     * @param image Source BufferedImage.
     * @param x Destination window X start coordinate.
     * @param y Destination window Y start coordinate.
     * @param width Window width.
     * @param height Window height.
     */
    @Override
    public final void drawImage(final BufferedImage image, final int x, final int y, final int width, final int height) {
        setWindow(x, y, width, height);
        final var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        final var imgWidth = image.getWidth();

        var destOffset = 0L;
        for (var sy = 0; sy < height; sy++) {
            for (var sx = 0; sx < width; sx++) {
                final var p = pixels[sy * imgWidth + sx];
                final var packed = (short) ((((p >> 19) & 0x1F) << 11) | (((p >> 10) & 0x3F) << 5) | ((p >> 3) & 0x1F));
                getImageSegment().set(ValueLayout.JAVA_SHORT_UNALIGNED, destOffset, Short.reverseBytes(packed));
                destOffset += 2L;
            }
        }
        writeData(getImageSegment().asSlice(0L, (long) width * height * 2L));
    }

    /**
     * Sets the active drawing window on the GC9A01 display controller.
     *
     * @param x X start coordinate.
     * @param y Y start coordinate.
     * @param width Window width.
     * @param height Window height.
     */
    @Override
    public final void setWindow(final int x, final int y, final int width, final int height) {
        writeCommand(new byte[]{
            (byte) 0x2A,
            (byte) (x >> 8),
            (byte) (x & 0xFF),
            (byte) ((x + width - 1) >> 8),
            (byte) ((x + width - 1) & 0xFF)
        });
        writeCommand(new byte[]{
            (byte) 0x2B,
            (byte) (y >> 8),
            (byte) (y & 0xFF),
            (byte) ((y + height - 1) >> 8),
            (byte) ((y + height - 1) & 0xFF)
        });
        writeCommand(new byte[]{(byte) 0x2C});
    }

    /**
     * Closes native SPI, GPIO, and PWM resources safely during shutdown routines.
     */
    @Override
    protected void closeNative() {
        log.debug("Closing GC9A01 LCD Display");
        try {
            if (getHandle().address() != 0 && getArena().scope().isAlive()) {
                if (pwmBacklight != null) {
                    pwmBacklight.disable();
                } else if (ledHandle.address() != 0) {
                    Periphery.gpio_write(ledHandle, false);
                }
                writeCommand(new byte[]{DISPOFF});
                writeCommand(new byte[]{SLPIN});
            }
        } catch (final Exception e) {
            System.err.printf("Error turning off display during emergency close: %s%n", e.getMessage());
        } finally {
            if (pwmBacklight != null) {
                try {
                    pwmBacklight.close();
                } catch (final Exception e) {
                    log.warn("Error closing PWM backlight: {}", e.getMessage());
                }
            }

            if (getHandle().address() != 0) {
                Periphery.spi_close(getHandle());
            }
            if (getDcHandle().address() != 0) {
                Periphery.gpio_close(getDcHandle());
            }
            if (rstHandle.address() != 0) {
                Periphery.gpio_close(rstHandle);
                Periphery.gpio_free(rstHandle);
            }
            if (ledHandle.address() != 0) {
                Periphery.gpio_close(ledHandle);
                Periphery.gpio_free(ledHandle);
            }
        }
    }
}
