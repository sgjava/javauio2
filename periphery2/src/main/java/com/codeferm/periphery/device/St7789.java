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
import org.periphery.gpio_handle;
import org.periphery.spi_handle;

/**
 * ST7789 240x320 RGB IPS LCD module driver using Java Foreign Function & Memory (FFM) API.
 * <p>
 * This driver provides a high-performance interface to the ST7789 controller, utilizing {@link MemorySegment} for zero-copy data
 * transfers. Optimized with a zero-allocation strategy to prevent memory thrashing and OutOfMemoryErrors in tight loops. It
 * inherits automated safe-teardown from {@link AbstractDevice}.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class St7789 extends AbstractDevice {

    /**
     * libperiphery constant for output direction (1 = GPIO_DIR_OUT).
     */
    private static final int GPIO_DIR_OUT = 1;

    /**
     * Software Reset command.
     */
    public static final byte SWRESET = (byte) 0x01;

    /**
     * Sleep In command.
     */
    public static final byte SLPIN = (byte) 0x10;

    /**
     * Sleep Out command.
     */
    public static final byte SLPOUT = (byte) 0x11;

    /**
     * Normal Display Mode On.
     */
    public static final byte NORON = (byte) 0x13;

    /**
     * Display Inversion On.
     */
    public static final byte INVON = (byte) 0x21;

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
     * Porch Setting.
     */
    public static final byte PORCTRL = (byte) 0xB2;

    /**
     * Gate Control.
     */
    public static final byte GCTRL = (byte) 0xB7;

    /**
     * VCOM Setting.
     */
    public static final byte VCOMS = (byte) 0xBB;

    /**
     * LCM Control.
     */
    public static final byte LCMCTRL = (byte) 0xC0;

    /**
     * VDV and VRH Command Enable.
     */
    public static final byte VDVVRHEN = (byte) 0xC2;

    /**
     * VRH Set.
     */
    public static final byte VRHS = (byte) 0xc3;

    /**
     * VDV Set.
     */
    public static final byte VDVS = (byte) 0xC4;

    /**
     * Frame Rate Control in Normal Mode.
     */
    public static final byte FRCTRL2 = (byte) 0xC6;

    /**
     * Power Control 1.
     */
    public static final byte PWCTRL1 = (byte) 0xD0;

    /**
     * Positive Voltage Gamma Control.
     */
    public static final byte PVGAMCTRL = (byte) 0xE0;

    /**
     * Negative Voltage Gamma Control.
     */
    public static final byte NVGAMCTRL = (byte) 0xE1;

    /**
     * Native handle for the Data/Command GPIO pin.
     */
    private final MemorySegment dcHandle;

    /**
     * Reusable native segment for SPI commands to prevent heap thrashing.
     */
    private final MemorySegment commandSegment;

    /**
     * Reusable native segment for full-frame image data.
     */
    private final MemorySegment imageSegment;

    /**
     * Transfer chunk buffer size (defaults to 65536 bytes).
     */
    private final int bufferSize;

    /**
     * ST7789 display width in pixels.
     */
    @Getter
    private final int width = 240;

    /**
     * ST7789 display height in pixels.
     */
    @Getter
    private final int height = 320;

    /**
     * Initializes hardware with SPI and GPIO handles via FFM using a default 64KB chunk buffer.
     *
     * @param device SPI device path.
     * @param mode SPI mode.
     * @param speed SPI speed in Hz.
     * @param gpioDevice GPIO chip path.
     * @param dcPin Data/Command BCM pin number.
     */
    public St7789(final String device, final int mode, final int speed, final String gpioDevice, final int dcPin) {
        this(device, mode, speed, gpioDevice, dcPin, 65536);
    }

    /**
     * Initializes hardware with SPI and GPIO handles via FFM with a configurable buffer size.
     *
     * @param device SPI device path.
     * @param mode SPI mode.
     * @param speed SPI speed in Hz.
     * @param gpioDevice GPIO chip path.
     * @param dcPin Data/Command BCM pin number.
     * @param bufferSize Transfer buffer chunk size in bytes.
     */
    public St7789(final String device, final int mode, final int speed, final String gpioDevice, final int dcPin,
            final int bufferSize) {
        super(spi_handle.layout());
        this.bufferSize = bufferSize;
        this.dcHandle = getArena().allocate(gpio_handle.layout());
        this.commandSegment = getArena().allocate(64);
        this.imageSegment = getArena().allocate((long) width * height * 2);
        final var cDevice = getArena().allocateFrom(device);
        final var cGpioDev = getArena().allocateFrom(gpioDevice);
        if (Periphery.spi_open(getHandle(), cDevice, mode, speed) < 0) {
            throw new RuntimeException("SPI open failed");
        }
        if (Periphery.gpio_open(dcHandle, cGpioDev, dcPin, GPIO_DIR_OUT) < 0) {
            throw new RuntimeException("DC GPIO open failed");
        }
        setup();
    }

    /**
     * Sends command bytes and optional parameters, correctly managing D/C line state transitions (D/C LOW for command, D/C HIGH for
     * parameters).
     *
     * @param data Command array with optional parameter bytes.
     */
    public final void writeCommand(final byte[] data) {
        if (getHandle().address() != 0 && getArena().scope().isAlive()) {
            // Command byte with D/C LOW
            Periphery.gpio_write(dcHandle, false);
            commandSegment.set(ValueLayout.JAVA_BYTE, 0L, data[0]);
            if (Periphery.spi_transfer(getHandle(), commandSegment, MemorySegment.NULL, 1) < 0) {
                throw new RuntimeException("SPI Command failed");
            }
            // Parameter bytes with D/C HIGH
            if (data.length > 1) {
                Periphery.gpio_write(dcHandle, true);
                MemorySegment.copy(data, 1, commandSegment, ValueLayout.JAVA_BYTE, 0, data.length - 1);
                if (Periphery.spi_transfer(getHandle(), commandSegment, MemorySegment.NULL, data.length - 1) < 0) {
                    throw new RuntimeException("SPI Command parameters failed");
                }
            }
        }
    }

    /**
     * Sends data bytes (pixels) directly from a {@link MemorySegment} in optimized chunks using write-only configuration
     * (`MemorySegment.NULL`).
     *
     * @param segment Native segment containing pixel data.
     */
    public final void writeData(final MemorySegment segment) {
        if (getHandle().address() != 0 && getArena().scope().isAlive()) {
            Periphery.gpio_write(dcHandle, true);
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
     * Sends data bytes (pixels) from a Java array.
     *
     * @param data Pixel data array.
     */
    public final void writeData(final byte[] data) {
        final var cData = getArena().allocateFrom(ValueLayout.JAVA_BYTE, data);
        writeData(cData);
    }

    /**
     * Performs software reset and complete initialization sequence for 2.0-inch ST7789 IPS panels.
     */
    public final void setup() {
        try {
            Periphery.gpio_write(dcHandle, false);
            TimeUnit.MILLISECONDS.sleep(150);
            writeCommand(new byte[]{SWRESET});
            TimeUnit.MILLISECONDS.sleep(150);
            writeCommand(new byte[]{SLPOUT});
            TimeUnit.MILLISECONDS.sleep(500);
            writeCommand(new byte[]{COLMOD, (byte) 0x55}); // 16-bit RGB565
            // MADCTL configuration: Column/Row Exchange (MV) and Refresh Directions 
            // set to prevent off-screen shifting and quarter-screen clipping.
            writeCommand(new byte[]{MADCTL, (byte) 0x00});
            writeCommand(new byte[]{PORCTRL, (byte) 0x0c, (byte) 0x0c, (byte) 0x00, (byte) 0x33, (byte) 0x33});
            writeCommand(new byte[]{GCTRL, (byte) 0x35});
            writeCommand(new byte[]{VCOMS, (byte) 0x35});
            writeCommand(new byte[]{LCMCTRL, (byte) 0x2c});
            writeCommand(new byte[]{VDVVRHEN, (byte) 0x01});
            writeCommand(new byte[]{VRHS, (byte) 0x13});
            writeCommand(new byte[]{VDVS, (byte) 0x20});
            writeCommand(new byte[]{FRCTRL2, (byte) 0x0f});
            writeCommand(new byte[]{PWCTRL1, (byte) 0xa4, (byte) 0xa1});
            writeCommand(new byte[]{PVGAMCTRL, (byte) 0xd0, (byte) 0x04, (byte) 0x0d, (byte) 0x11, (byte) 0x13, (byte) 0x2b,
                (byte) 0x3f, (byte) 0x54, (byte) 0x4c, (byte) 0x18, (byte) 0x0d, (byte) 0x0b, (byte) 0x1f, (byte) 0x23});
            writeCommand(new byte[]{NVGAMCTRL, (byte) 0xd0, (byte) 0x04, (byte) 0x0c, (byte) 0x11, (byte) 0x13, (byte) 0x2c,
                (byte) 0x3f, (byte) 0x44, (byte) 0x51, (byte) 0x2f, (byte) 0x1f, (byte) 0x1f, (byte) 0x20, (byte) 0x23});
            writeCommand(new byte[]{INVON}); // IPS panels usually require inversion ON
            writeCommand(new byte[]{NORON});
            TimeUnit.MILLISECONDS.sleep(10);
            writeCommand(new byte[]{DISPON});
            TimeUnit.MILLISECONDS.sleep(100);
            clear();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Clears the display matching the exact frame boundaries.
     */
    public final void clear() {
        writeCommand(new byte[]{CASET, (byte) 0x00, (byte) 0x00, (byte) ((width - 1) >> 8), (byte) (width - 1)});
        writeCommand(new byte[]{RASET, (byte) 0x00, (byte) 0x00, (byte) ((height - 1) >> 8), (byte) (height - 1)});
        writeCommand(new byte[]{RAMWR});
        imageSegment.fill((byte) 0);
        writeData(imageSegment);
    }

    /**
     * Maps a {@link BufferedImage} to RGB565 and renders via pre-allocated segment.
     *
     * @param image BufferedImage to render.
     */
    public final void drawImage(final BufferedImage image) {
        writeCommand(new byte[]{CASET, (byte) 0x00, (byte) 0x00, (byte) ((width - 1) >> 8), (byte) (width - 1)});
        writeCommand(new byte[]{RASET, (byte) 0x00, (byte) 0x00, (byte) ((height - 1) >> 8), (byte) (height - 1)});
        writeCommand(new byte[]{RAMWR});

        final var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (var i = 0; i < pixels.length; i++) {
            final var p = pixels[i];
            final var packed = (short) ((((p >> 19) & 0x1F) << 11) | (((p >> 10) & 0x3F) << 5) | ((p >> 3) & 0x1F));
            imageSegment.set(ValueLayout.JAVA_SHORT_UNALIGNED, i * 2L, Short.reverseBytes(packed));
        }

        writeData(imageSegment);
    }

    /**
     * Closes native SPI and GPIO resources safely during shutdown routines.
     */
    @Override
    protected void closeNative() {
        log.debug("Closing ST7789 LCD Display");
        try {
            if (getHandle().address() != 0 && getArena().scope().isAlive()) {
                writeCommand(new byte[]{DISPOFF});
                writeCommand(new byte[]{SLPIN});
            }
        } catch (final Exception e) {
            System.err.printf("Error turning off display during emergency close: %s%n", e.getMessage());
        } finally {
            if (getHandle().address() != 0) {
                Periphery.spi_close(getHandle());
            }
            if (dcHandle.address() != 0) {
                Periphery.gpio_close(dcHandle);
            }
        }
    }
}
