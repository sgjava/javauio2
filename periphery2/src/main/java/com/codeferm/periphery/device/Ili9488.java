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
 * ILI9488 320x480 TFT LCD module driver using Java Foreign Function & Memory (FFM) API, extending {@link AbstractColorDisplay}.
 * <p>
 * Fully generic implementation matching the architectural behavior of ST7789 and ILI9341 drivers, with automatic RAMWR command
 * insertion on writeData transfers and 16-bit RGB565 (`0x55`) Big-Endian compatibility.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class Ili9488 extends AbstractColorDisplay {

    public static final byte SWRESET = (byte) 0x01;
    public static final byte SLPIN = (byte) 0x10;
    public static final byte SLPOUT = (byte) 0x11;
    public static final byte NORON = (byte) 0x13;
    public static final byte DISPOFF = (byte) 0x28;
    public static final byte DISPON = (byte) 0x29;
    public static final byte CASET = (byte) 0x2A;
    public static final byte RASET = (byte) 0x2B;
    public static final byte RAMWR = (byte) 0x2C;
    public static final byte MADCTL = (byte) 0x36;
    public static final byte COLMOD = (byte) 0x3A;
    public static final byte IFMODE = (byte) 0xB0;
    public static final byte PVGAMCTRL = (byte) 0xE0;
    public static final byte NVGAMCTRL = (byte) 0xE1;

    @Getter
    private final MemorySegment rstHandle;

    @Getter
    private final MemorySegment ledHandle;

    private final PwmBacklight pwmBacklight;
    private final boolean hasReset;

    public Ili9488(final String device, final int mode, final int speed, final String gpioDevice, final int dcPin,
            final int rstPin, final int ledPin) {
        this(device, mode, speed, gpioDevice, dcPin, rstPin, ledPin, 65536);
    }

    public Ili9488(final String device, final int mode, final int speed, final String gpioDevice, final int dcPin,
            final int rstPin, final int ledPin, final int bufferSize) {
        super(320, 480, bufferSize, 2);
        this.hasReset = rstPin >= 0;
        this.rstHandle = hasReset ? Periphery.gpio_new() : MemorySegment.NULL;
        this.ledHandle = ledPin >= 0 ? Periphery.gpio_new() : MemorySegment.NULL;
        this.pwmBacklight = null;

        if (hasReset && rstHandle.address() == 0) {
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
        if (hasReset) {
            if (Periphery.gpio_open(rstHandle, cGpioDev, rstPin, GPIO_DIR_OUT) < 0) {
                throw new RuntimeException("RST GPIO open failed");
            }
        }
        if (ledPin >= 0) {
            if (Periphery.gpio_open(ledHandle, cGpioDev, ledPin, GPIO_DIR_OUT) < 0) {
                throw new RuntimeException("LED Backlight GPIO open failed");
            }
        }
        setup();
    }

    public Ili9488(final String device, final int mode, final int speed, final String gpioDevice, final int dcPin,
            final int rstPin, final PwmBacklight pwmBacklight, final int bufferSize) {
        super(320, 480, bufferSize, 2);
        this.hasReset = rstPin >= 0;
        this.rstHandle = hasReset ? Periphery.gpio_new() : MemorySegment.NULL;
        this.ledHandle = MemorySegment.NULL;
        this.pwmBacklight = pwmBacklight;

        if (hasReset && rstHandle.address() == 0) {
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
        if (hasReset) {
            if (Periphery.gpio_open(rstHandle, cGpioDev, rstPin, GPIO_DIR_OUT) < 0) {
                throw new RuntimeException("RST GPIO open failed");
            }
        }
        setup();
    }

    @Override
    public final void setRotation(final int rotation) {
        super.setRotation(rotation);
        final var mode = (this.rotation / 90) % 4;
        byte madctlValue;
        switch (mode) {
            case 1:
                madctlValue = (byte) 0x28; // 90 degrees Landscape (MV | BGR)
                break;
            case 2:
                madctlValue = (byte) 0x88; // 180 degrees Portrait (MY | BGR)
                break;
            case 3:
                madctlValue = (byte) 0xE8; // 270 degrees Landscape (MX | MY | MV | BGR)
                break;
            case 0:
            default:
                madctlValue = (byte) 0x48; // 0 degrees Portrait (MX | BGR)
                break;
        }
        if (getHandle().address() != 0 && getArena().scope().isAlive()) {
            writeCommand(new byte[]{MADCTL, madctlValue});
        }
    }

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
     * Automatically arms the controller with RAMWR (`0x2C`) before transferring pixel data chunks, maintaining complete
     * compatibility with standard application code and other display drivers.
     *
     * @param segment Native segment containing pixel data.
     */
    @Override
    public final void writeData(final MemorySegment segment) {
        if (getHandle().address() != 0 && getArena().scope().isAlive()) {
            // Automatically issue RAMWR before writing pixel data streams
            writeCommand(new byte[]{RAMWR});

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

    public final void setup() {
        try {
            if (pwmBacklight != null) {
                pwmBacklight.enable();
                pwmBacklight.setBrightness(1_000_000L, 1.0);
            } else if (ledHandle.address() != 0) {
                Periphery.gpio_write(ledHandle, true);
            }

            if (hasReset) {
                Periphery.gpio_write(rstHandle, false);
                TimeUnit.MILLISECONDS.sleep(20);
                Periphery.gpio_write(rstHandle, true);
                TimeUnit.MILLISECONDS.sleep(150);
            }

            Periphery.gpio_write(getDcHandle(), false);
            TimeUnit.MILLISECONDS.sleep(150);
            writeCommand(new byte[]{SWRESET});
            TimeUnit.MILLISECONDS.sleep(150);
            writeCommand(new byte[]{SLPOUT});
            TimeUnit.MILLISECONDS.sleep(150);

            // Interface Pixel Format: 0x55 = 16-bit RGB565
            writeCommand(new byte[]{COLMOD, (byte) 0x55});

            // Frame Rate Control
            writeCommand(new byte[]{(byte) 0xB1, (byte) 0xA0});

            // Display Inversion Control
            writeCommand(new byte[]{(byte) 0xB4, (byte) 0x02});

            // Display Function Control
            writeCommand(new byte[]{(byte) 0xB6, (byte) 0x02, (byte) 0x02, (byte) 0x3B});

            // Positive Voltage Gamma Control
            writeCommand(new byte[]{PVGAMCTRL,
                (byte) 0x00, (byte) 0x03, (byte) 0x09, (byte) 0x08, (byte) 0x16, (byte) 0x0A,
                (byte) 0x3F, (byte) 0x78, (byte) 0x4C, (byte) 0x09, (byte) 0x0A, (byte) 0x08,
                (byte) 0x16, (byte) 0x1A, (byte) 0x0F});

            // Negative Voltage Gamma Control
            writeCommand(new byte[]{NVGAMCTRL,
                (byte) 0x00, (byte) 0x16, (byte) 0x19, (byte) 0x03, (byte) 0x0F, (byte) 0x05,
                (byte) 0x32, (byte) 0x45, (byte) 0x46, (byte) 0x04, (byte) 0x0E, (byte) 0x0D,
                (byte) 0x35, (byte) 0x37, (byte) 0x0F});

            setRotation(rotation);

            writeCommand(new byte[]{NORON});
            TimeUnit.MILLISECONDS.sleep(10);
            writeCommand(new byte[]{DISPON});
            TimeUnit.MILLISECONDS.sleep(150);
            clear();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public final void clear() {
        setWindow(0, 0, getWidth(), getHeight());
        getImageSegment().fill((byte) 0);
        writeData(getImageSegment());
    }

    @Override
    public final void drawPixel(final int x, final int y, final int color) {
        if (x >= 0 && x < getWidth() && y >= 0 && y < getHeight()) {
            final var p = color;
            final var r = (p >> 16) & 0xFF;
            final var g = (p >> 8) & 0xFF;
            final var b = p & 0xFF;
            final var rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);
            final var offset = (long) (y * getWidth() + x) * 2L;

            getImageSegment().set(ValueLayout.JAVA_BYTE, offset, (byte) (rgb565 >> 8));
            getImageSegment().set(ValueLayout.JAVA_BYTE, offset + 1L, (byte) rgb565);

            setWindow(x, y, 1, 1);
            writeData(getImageSegment().asSlice(offset, 2L));
        }
    }

    @Override
    public final void fillRect(final int x, final int y, final int width, final int height, final int color) {
        if (x >= 0 && y >= 0 && x + width <= getWidth() && y + height <= getHeight()) {
            setWindow(x, y, width, height);
            final var totalBytes = (long) width * height * 2L;
            final var fillSegment = getArena().allocate(totalBytes);

            final var p = color;
            final var r = (p >> 16) & 0xFF;
            final var g = (p >> 8) & 0xFF;
            final var b = p & 0xFF;
            final var rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);
            final byte highByte = (byte) (rgb565 >> 8);
            final byte lowByte = (byte) rgb565;

            for (var i = 0L; i < totalBytes; i += 2L) {
                fillSegment.set(ValueLayout.JAVA_BYTE, i, highByte);
                fillSegment.set(ValueLayout.JAVA_BYTE, i + 1L, lowByte);
            }
            writeData(fillSegment);
        }
    }

    @Override
    public final void drawImage(final BufferedImage image) {
        setWindow(0, 0, getWidth(), getHeight());
        packRgb888ToRgb565(image);
        writeData(getImageSegment());
    }

    @Override
    public final void drawImage(final BufferedImage image, final int x, final int y, final int width, final int height) {
        setWindow(x, y, width, height);
        final var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        final var imgWidth = image.getWidth();

        var destOffset = 0L;
        for (var sy = 0; sy < height; sy++) {
            for (var sx = 0; sx < width; sx++) {
                final var p = pixels[sy * imgWidth + sx];
                final var r = (p >> 16) & 0xFF;
                final var g = (p >> 8) & 0xFF;
                final var b = p & 0xFF;
                final var rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);

                getImageSegment().set(ValueLayout.JAVA_BYTE, destOffset, (byte) (rgb565 >> 8));
                getImageSegment().set(ValueLayout.JAVA_BYTE, destOffset + 1L, (byte) rgb565);
                destOffset += 2L;
            }
        }
        writeData(getImageSegment().asSlice(0L, (long) width * height * 2L));
    }

    @Override
    public final void setWindow(final int x, final int y, final int width, final int height) {
        final var xStart = x;
        final var xEnd = x + width - 1;
        final var yStart = y;
        final var yEnd = y + height - 1;

        writeCommand(new byte[]{
            CASET,
            (byte) (xStart >> 8),
            (byte) (xStart & 0xFF),
            (byte) (xEnd >> 8),
            (byte) (xEnd & 0xFF)
        });
        writeCommand(new byte[]{
            RASET,
            (byte) (yStart >> 8),
            (byte) (yStart & 0xFF),
            (byte) (yEnd >> 8),
            (byte) (yEnd & 0xFF)
        });
    }

@Override
    protected void closeNative() {
        log.debug("Closing ILI9488 LCD Display");
        try {
            // Always ensure backlight is killed first regardless of state
            if (pwmBacklight != null) {
                try {
                    pwmBacklight.disable();
                } catch (final Exception e) {
                    log.debug("PWM backlight already disabled: {}", e.getMessage());
                }
            } else if (ledHandle.address() != 0) {
                Periphery.gpio_write(ledHandle, false);
            }

            if (getHandle().address() != 0 && getArena().scope().isAlive()) {
                writeCommand(new byte[]{DISPOFF});
                writeCommand(new byte[]{SLPIN});
            }
        } catch (final Exception e) {
            log.debug("Display power-off commands skipped or failed: {}", e.getMessage());
        } finally {
            if (pwmBacklight != null) {
                try {
                    pwmBacklight.close();
                } catch (final Exception e) {
                    log.debug("PWM backlight already closed: {}", e.getMessage());
                }
            }

            if (getHandle().address() != 0) {
                Periphery.spi_close(getHandle());
            }
            if (getDcHandle().address() != 0) {
                Periphery.gpio_close(getDcHandle());
            }
            if (hasReset && rstHandle.address() != 0) {
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
