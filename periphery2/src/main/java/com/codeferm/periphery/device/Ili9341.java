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
 * ILI9341 240x320 RGB IPS LCD module driver using Java Foreign Function & Memory (FFM) API, extending {@link AbstractColorDisplay}.
 * <p>
 * This driver provides a high-performance interface to the ILI9341 controller, utilizing {@link MemorySegment} for zero-copy data
 * transfers. Optimized with a zero-allocation strategy to prevent memory thrashing and OutOfMemoryErrors in tight loops. It
 * inherits automated safe-teardown from {@link AbstractDevice} and implements pixel and shape drawing via software rasterization.
 * Supports rotation orientations (0, 90, 180, 270 degrees).
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.1.0
 * @since 1.0.0
 */
@Slf4j
public class Ili9341 extends AbstractColorDisplay {

    /**
     * Software Reset command.
     */
    public static final byte SWRESET = (byte) 0x01;

    /**
     * Sleep Out command.
     */
    public static final byte SLPOUT = (byte) 0x11;

    /**
     * Normal Display Mode On.
     */
    public static final byte NORON = (byte) 0x13;

    /**
     * Gamma Curve Selected.
     */
    public static final byte GAMSET = (byte) 0x26;

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
     * Frame Rate Control (In Normal Mode/Full Colors).
     */
    public static final byte FRMCTR1 = (byte) 0xB1;

    /**
     * Display Function Control.
     */
    public static final byte DFUNCTR = (byte) 0xB6;

    /**
     * Power Control 1.
     */
    public static final byte PWCTRL1 = (byte) 0xC0;

    /**
     * Power Control 2.
     */
    public static final byte PWCTRL2 = (byte) 0xC1;

    /**
     * VCOM Control 1.
     */
    public static final byte VMCTR1 = (byte) 0xC5;

    /**
     * VCOM Control 2.
     */
    public static final byte VMCTR2 = (byte) 0xC7;

    /**
     * Power Control A.
     */
    public static final byte PWCTRA = (byte) 0xCB;

    /**
     * Power Control B.
     */
    public static final byte PWCTRB = (byte) 0xCF;

    /**
     * Driver Timing Control A.
     */
    public static final byte DTCTRLA = (byte) 0xE8;

    /**
     * Driver Timing Control B.
     */
    public static final byte DTCTRLB = (byte) 0xEA;

    /**
     * Power On Sequence Control.
     */
    public static final byte PWOFFS = (byte) 0xED;

    /**
     * 3G Function Control.
     */
    public static final byte FUNCTR3G = (byte) 0xF2;

    /**
     * Pump Ratio Control.
     */
    public static final byte PUMPRAT = (byte) 0xF7;

    /**
     * Sleep In command.
     */
    public static final byte SLPIN = (byte) 0x10;

    /**
     * Reset GPIO handle.
     */
    @Getter
    private final MemorySegment rstHandle;

    /**
     * Backlight LED GPIO handle.
     */
    @Getter
    private final MemorySegment ledHandle;

    /**
     * Initializes hardware with SPI, DC, Reset, and LED handles via FFM using a default 64KB chunk buffer.
     *
     * @param device SPI device path.
     * @param mode SPI mode.
     * @param speed SPI speed in Hz.
     * @param gpioDevice GPIO chip path.
     * @param dcPin Data/Command pin number.
     * @param rstPin Reset pin number.
     * @param ledPin Backlight LED pin number.
     */
    public Ili9341(final String device, final int mode, final int speed, final String gpioDevice, final int dcPin,
            final int rstPin, final int ledPin) {
        this(device, mode, speed, gpioDevice, dcPin, rstPin, ledPin, 65536);
    }

    /**
     * Initializes hardware with SPI, DC, Reset, and LED handles via FFM with a configurable buffer size.
     *
     * @param device SPI device path.
     * @param mode SPI mode.
     * @param speed SPI speed in Hz.
     * @param gpioDevice GPIO chip path.
     * @param dcPin Data/Command pin number.
     * @param rstPin Reset pin number.
     * @param ledPin Backlight LED pin number.
     * @param bufferSize Transfer buffer chunk size in bytes.
     */
    public Ili9341(final String device, final int mode, final int speed, final String gpioDevice, final int dcPin,
            final int rstPin, final int ledPin, final int bufferSize) {
        super(240, 320, bufferSize);
        rstHandle = Periphery.gpio_new();
        ledHandle = Periphery.gpio_new();

        if (rstHandle.address() == 0 || ledHandle.address() == 0) {
            throw new RuntimeException("Failed to allocate native Reset or LED GPIO handles");
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
            throw new RuntimeException("Reset GPIO open failed");
        }
        if (Periphery.gpio_open(ledHandle, cGpioDev, ledPin, GPIO_DIR_OUT) < 0) {
            throw new RuntimeException("LED Backlight GPIO open failed");
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
                madctlValue = (byte) 0x28; // 90 degrees
                break;
            case 2:
                madctlValue = (byte) 0xC8; // 180 degrees
                break;
            case 3:
                madctlValue = (byte) 0xE8; // 270 degrees
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
     * Sends command bytes and optional parameters, correctly managing D/C line state transitions (D/C LOW for command, D/C HIGH for
     * parameters).
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
     * Sends data bytes (pixels) directly from a {@link MemorySegment} in optimized chunks using write-only configuration
     * (`MemorySegment.NULL`).
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
     * Performs hardware reset sequence, complete initialization, backlight activation, and clear routine for ILI9341 IPS panels.
     */
    public final void setup() {
        try {
            Periphery.gpio_write(ledHandle, true);

            Periphery.gpio_write(rstHandle, true);
            TimeUnit.MILLISECONDS.sleep(50);
            Periphery.gpio_write(rstHandle, false);
            TimeUnit.MILLISECONDS.sleep(100);
            Periphery.gpio_write(rstHandle, true);
            TimeUnit.MILLISECONDS.sleep(150);

            writeCommand(new byte[]{SWRESET});
            TimeUnit.MILLISECONDS.sleep(100);

            writeCommand(new byte[]{DISPOFF});

            writeCommand(new byte[]{PWCTRA, (byte) 0x39, (byte) 0x2C, (byte) 0x00, (byte) 0x34, (byte) 0x02});
            writeCommand(new byte[]{PWCTRB, (byte) 0x00, (byte) 0xC1, (byte) 0x30});
            writeCommand(new byte[]{DTCTRLA, (byte) 0x85, (byte) 0x00, (byte) 0x78});
            writeCommand(new byte[]{DTCTRLB, (byte) 0x00, (byte) 0x00});
            writeCommand(new byte[]{PWOFFS, (byte) 0x64, (byte) 0x03, (byte) 0x12, (byte) 0x81});
            writeCommand(new byte[]{PUMPRAT, (byte) 0x20});
            writeCommand(new byte[]{PWCTRL1, (byte) 0x23});
            writeCommand(new byte[]{PWCTRL2, (byte) 0x10});
            writeCommand(new byte[]{VMCTR1, (byte) 0x3e, (byte) 0x28});
            writeCommand(new byte[]{VMCTR2, (byte) 0x86});

            setRotation(rotation);

            writeCommand(new byte[]{COLMOD, (byte) 0x55});
            writeCommand(new byte[]{FRMCTR1, (byte) 0x00, (byte) 0x18});
            writeCommand(new byte[]{DFUNCTR, (byte) 0x08, (byte) 0x82, (byte) 0x27});
            writeCommand(new byte[]{FUNCTR3G, (byte) 0x00});
            writeCommand(new byte[]{GAMSET, (byte) 0x01});

            writeCommand(new byte[]{SLPOUT});
            TimeUnit.MILLISECONDS.sleep(120);

            writeCommand(new byte[]{DISPON});
            TimeUnit.MILLISECONDS.sleep(50);

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
     * Maps a sub-region of a {@link BufferedImage} to RGB565 and renders it to a specific window on the display.
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
     * Sets the active drawing window on the ILI9341 display controller.
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
     * Closes native SPI and GPIO resources safely during shutdown routines.
     */
    @Override
    protected void closeNative() {
        log.debug("Closing ILI9341 LCD Display");
        try {
            if (getHandle().address() != 0 && getArena().scope().isAlive()) {
                Periphery.gpio_write(ledHandle, false);
                writeCommand(new byte[]{DISPOFF});
                writeCommand(new byte[]{SLPIN});
            }
        } catch (final Exception e) {
            System.err.printf("Error turning off display during emergency close: %s%n", e.getMessage());
        } finally {
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
