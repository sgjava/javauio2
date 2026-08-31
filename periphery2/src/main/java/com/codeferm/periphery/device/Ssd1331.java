/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;

/**
 * SSD1331 96x64 RGB OLED driver using Java Foreign Function & Memory (FFM) API, extending {@link AbstractColorDisplay}.
 * <p>
 * This driver provides a high-performance interface to the SSD1331 controller, utilizing {@link MemorySegment} for zero-copy data
 * transfers and supporting all Hardware Graphic Acceleration Commands (GAC) overrides, along with display rotation. Optimized with
 * a zero-allocation strategy to prevent memory thrashing.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class Ssd1331 extends AbstractColorDisplay {

    /**
     * Set column start and end address.
     */
    public static final byte SET_COLUMN_ADDRESS = (byte) 0x15;

    /**
     * Set row start and end address.
     */
    public static final byte SET_ROW_ADDRESS = (byte) 0x75;

    /**
     * Set contrast for color A (Red).
     */
    public static final byte SET_CONTRAST_A = (byte) 0x81;

    /**
     * Set contrast for color B (Green).
     */
    public static final byte SET_CONTRAST_B = (byte) 0x82;

    /**
     * Set contrast for color C (Blue).
     */
    public static final byte SET_CONTRAST_C = (byte) 0x83;

    /**
     * Master current control (0-15).
     */
    public static final byte MASTER_CURRENT_CONTROL = (byte) 0x87;

    /**
     * Set re-map and color depth.
     */
    public static final byte SET_REMAP = (byte) 0xA0;

    /**
     * Set display start line.
     */
    public static final byte SET_DISPLAY_START_LINE = (byte) 0xA1;

    /**
     * Set display offset.
     */
    public static final byte SET_DISPLAY_OFFSET = (byte) 0xA2;

    /**
     * Normal display mode.
     */
    public static final byte NORMAL_DISPLAY = (byte) 0xA4;

    /**
     * Set multiplex ratio.
     */
    public static final byte SET_MULTIPLEX_RATIO = (byte) 0xA8;

    /**
     * Master configuration.
     */
    public static final byte MASTER_CONFIGURATION = (byte) 0xAD;

    /**
     * Power save mode.
     */
    public static final byte POWER_SAVE_MODE = (byte) 0xB0;

    /**
     * Phase 1 and 2 period adjustment.
     */
    public static final byte PHASE_1_2_PERIOD = (byte) 0xB1;

    /**
     * Set display clock divide ratio/oscillator frequency.
     */
    public static final byte DISPLAY_CLOCK_DIV = (byte) 0xB3;

    /**
     * Set pre-charge voltage.
     */
    public static final byte PRECHARGE_VOLTAGE = (byte) 0xBB;

    /**
     * Set Vcomh voltage.
     */
    public static final byte SET_VCOMH_VOLTAGE = (byte) 0xBE;

    /**
     * No operation command.
     */
    public static final byte NO_OP = (byte) 0xBC;

    /**
     * Power off display.
     */
    public static final byte DISPLAY_OFF = (byte) 0xAE;

    /**
     * Power on display.
     */
    public static final byte DISPLAY_ON = (byte) 0xAF;

    /**
     * GAC: Draw line.
     */
    public static final byte DRAW_LINE = (byte) 0x21;

    /**
     * GAC: Draw rectangle.
     */
    public static final byte DRAW_RECTANGLE = (byte) 0x22;

    /**
     * GAC: Copy window.
     */
    public static final byte COPY_WINDOW = (byte) 0x23;

    /**
     * GAC: Clear window.
     */
    public static final byte CLEAR_WINDOW = (byte) 0x25;

    /**
     * GAC: Fill enable/disable.
     */
    public static final byte FILL_ENABLE = (byte) 0x26;

    /**
     * GAC: Continuous scrolling setup.
     */
    public static final byte SET_SCROLLING = (byte) 0x27;

    /**
     * GAC: Deactivate scrolling.
     */
    public static final byte DEACTIVATE_SCROLLING = (byte) 0x2E;

    /**
     * GAC: Activate scrolling.
     */
    public static final byte ACTIVATE_SCROLLING = (byte) 0x2F;

    /**
     * Native handle for the Hardware Reset GPIO pin.
     */
    private final MemorySegment resHandle;

    /**
     * Initialize hardware with SPI and GPIO handles via FFM using default 64KB buffer size.
     *
     * @param device SPI device path.
     * @param mode SPI mode.
     * @param speed SPI speed in Hz.
     * @param gpioDevice GPIO chip path.
     * @param dcPin Data/Command BCM pin number.
     * @param resPin Reset BCM pin number.
     */
    public Ssd1331(final String device, final int mode, final int speed,
            final String gpioDevice, final int dcPin, final int resPin) {
        this(device, mode, speed, gpioDevice, dcPin, resPin, 65536);
    }

    /**
     * Initialize hardware with SPI and GPIO handles via FFM with configurable buffer size.
     *
     * @param device SPI device path.
     * @param mode SPI mode.
     * @param speed SPI speed in Hz.
     * @param gpioDevice GPIO chip path.
     * @param dcPin Data/Command BCM pin number.
     * @param resPin Reset BCM pin number.
     * @param bufferSize Transfer buffer chunk size in bytes.
     */
    public Ssd1331(final String device, final int mode, final int speed,
            final String gpioDevice, final int dcPin, final int resPin, final int bufferSize) {
        super(96, 64, bufferSize);

        this.resHandle = getArena().allocate(gpio_handle.layout());

        final var cDevice = getArena().allocateFrom(device);
        final var cGpioDev = getArena().allocateFrom(gpioDevice);

        if (Periphery.spi_open(getHandle(), cDevice, mode, speed) < 0) {
            throw new RuntimeException("SPI open failed");
        }
        if (Periphery.gpio_open(dcHandle, cGpioDev, dcPin, GPIO_DIR_OUT) < 0) {
            throw new RuntimeException("DC GPIO open failed");
        }
        if (Periphery.gpio_open(resHandle, cGpioDev, resPin, GPIO_DIR_OUT) < 0) {
            throw new RuntimeException("RES GPIO open failed");
        }
        setup();
    }

    /**
     * Sets the display rotation and configures re-map settings and dimensions accordingly.
     *
     * @param rotation Rotation angle in degrees (0, 90, 180, 270).
     */
    @Override
    public final void setRotation(final int rotation) {
        super.setRotation(rotation);
        if (getHandle().address() != 0 && getArena().scope().isAlive()) {
            final int remapVal = switch (rotation) {
                case 0 ->
                    0x72;   // Normal 
                case 90 ->
                    0x71;  // Rotated 90
                case 180 ->
                    0x70; // Rotated 180
                case 270 ->
                    0x73; // Rotated 270
                default ->
                    0x72;
            };
            writeCommand(new byte[]{SET_REMAP, (byte) remapVal});
        }
    }

    /**
     * Sends command bytes using the pre-allocated commandSegment.
     *
     * @param data Command array.
     */
    @Override
    public final void writeCommand(final byte[] data) {
        if (getHandle().address() != 0 && getArena().scope().isAlive()) {
            Periphery.gpio_write(dcHandle, false);
            MemorySegment.copy(data, 0, getCommandSegment(), ValueLayout.JAVA_BYTE, 0, data.length);
            if (Periphery.spi_transfer(getHandle(), getCommandSegment(), getCommandSegment(), data.length) < 0) {
                throw new RuntimeException("SPI Command failed");
            }
        }
    }

    /**
     * Sends data bytes (pixels) directly from a {@link MemorySegment} in optimized chunks (DC pin HIGH).
     *
     * @param segment Native segment containing pixel data.
     */
    @Override
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
     * Performs hardware reset and configuration sequence.
     */
    public final void setup() {
        try {
            Periphery.gpio_write(dcHandle, false);
            Periphery.gpio_write(resHandle, true);
            TimeUnit.MILLISECONDS.sleep(100);
            Periphery.gpio_write(resHandle, false);
            TimeUnit.MILLISECONDS.sleep(500);
            Periphery.gpio_write(resHandle, true);
            TimeUnit.MILLISECONDS.sleep(500);
            writeCommand(new byte[]{DISPLAY_OFF});

            // Apply rotation configuration during setup
            setRotation(getRotation());

            writeCommand(new byte[]{SET_DISPLAY_START_LINE, (byte) 0x00});
            writeCommand(new byte[]{SET_DISPLAY_OFFSET, (byte) 0x00});
            writeCommand(new byte[]{NORMAL_DISPLAY});
            writeCommand(new byte[]{SET_MULTIPLEX_RATIO, (byte) 0x3F});
            writeCommand(new byte[]{MASTER_CONFIGURATION, (byte) 0x8E});
            writeCommand(new byte[]{POWER_SAVE_MODE, (byte) 0x0B});
            writeCommand(new byte[]{PHASE_1_2_PERIOD, (byte) 0x74});
            writeCommand(new byte[]{DISPLAY_CLOCK_DIV, (byte) 0xD0});
            writeCommand(new byte[]{PRECHARGE_VOLTAGE, (byte) 0x3E});
            writeCommand(new byte[]{SET_VCOMH_VOLTAGE, (byte) 0x3E});
            writeCommand(new byte[]{MASTER_CURRENT_CONTROL, (byte) 0x0F});
            writeCommand(new byte[]{SET_CONTRAST_A, (byte) 0xFF});
            writeCommand(new byte[]{SET_CONTRAST_B, (byte) 0xFF});
            writeCommand(new byte[]{SET_CONTRAST_C, (byte) 0xFF});
            writeCommand(new byte[]{DEACTIVATE_SCROLLING});
            writeCommand(new byte[]{DISPLAY_ON});
            clear();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Clears the display via GAC command.
     */
    @Override
    public final void clear() {
        writeCommand(new byte[]{CLEAR_WINDOW, (byte) 0, (byte) 0, (byte) (getWidth() - 1), (byte) (getHeight() - 1)});
    }

    /**
     * Draws a single pixel by delegating to a 1x1 hardware rectangle fill.
     *
     * @param x X coordinate.
     * @param y Y coordinate.
     * @param color RGB color integer.
     */
    @Override
    public final void drawPixel(final int x, final int y, final int color) {
        final var r = (color >> 16) & 0xFF;
        final var g = (color >> 8) & 0xFF;
        final var b = color & 0xFF;
        // Scale 8-bit color components to SSD1331 6-bit (0-63) range
        fillRect(x, y, 1, 1, r >> 2, g >> 2, b >> 2);
    }

    /**
     * Hardware-accelerated line drawing override using SSD1331 GAC line command.
     *
     * @param x0 Start X coordinate.
     * @param y0 Start Y coordinate.
     * @param x1 End X coordinate.
     * @param y1 End Y coordinate.
     * @param color RGB color integer.
     */
    @Override
    public final void drawLine(final int x0, final int y0, final int x1, final int y1, final int color) {
        final var r = ((color >> 16) & 0xFF) >> 2;
        final var g = ((color >> 8) & 0xFF) >> 2;
        final var b = (color & 0xFF) >> 2;
        writeCommand(new byte[]{
            DRAW_LINE,
            (byte) x0, (byte) y0,
            (byte) x1, (byte) y1,
            (byte) r, (byte) g, (byte) b
        });
    }

    /**
     * Hardware-accelerated rectangle drawing override using SSD1331 GAC rectangle command (unfilled).
     *
     * @param x Top-left X coordinate.
     * @param y Top-left Y coordinate.
     * @param width Rectangle width.
     * @param height Rectangle height.
     * @param color RGB color integer.
     */
    @Override
    public final void drawRect(final int x, final int y, final int width, final int height, final int color) {
        final var r = ((color >> 16) & 0xFF) >> 2;
        final var g = ((color >> 8) & 0xFF) >> 2;
        final var b = (color & 0xFF) >> 2;
        final var x2 = x + width - 1;
        final var y2 = y + height - 1;

        writeCommand(new byte[]{FILL_ENABLE, (byte) 0x00});
        writeCommand(new byte[]{
            DRAW_RECTANGLE,
            (byte) x, (byte) y,
            (byte) x2, (byte) y2,
            (byte) r, (byte) g, (byte) b,
            (byte) r, (byte) g, (byte) b
        });
    }

    /**
     * Hardware-accelerated filled rectangle drawing override using SSD1331 GAC rectangle command (filled).
     *
     * @param x Top-left X coordinate.
     * @param y Top-left Y coordinate.
     * @param width Rectangle width.
     * @param height Rectangle height.
     * @param color RGB color integer.
     */
    @Override
    public final void fillRect(final int x, final int y, final int width, final int height, final int color) {
        final var r = ((color >> 16) & 0xFF) >> 2;
        final var g = ((color >> 8) & 0xFF) >> 2;
        final var b = (color & 0xFF) >> 2;
        final var x2 = x + width - 1;
        final var y2 = y + height - 1;

        writeCommand(new byte[]{FILL_ENABLE, (byte) 0x01});
        writeCommand(new byte[]{
            DRAW_RECTANGLE,
            (byte) x, (byte) y,
            (byte) x2, (byte) y2,
            (byte) r, (byte) g, (byte) b,
            (byte) r, (byte) g, (byte) b
        });
    }

    /**
     * Hardware-accelerated filled rectangle drawing with raw 6-bit color components.
     *
     * @param x Left X.
     * @param y Top Y.
     * @param width Width.
     * @param height Height.
     * @param r Red (0-63).
     * @param g Green (0-63).
     * @param b Blue (0-63).
     */
    public final void fillRect(final int x, final int y, final int width, final int height,
            final int r, final int g, final int b) {
        final var x2 = x + width - 1;
        final var y2 = y + height - 1;
        writeCommand(new byte[]{FILL_ENABLE, (byte) 0x01});
        writeCommand(new byte[]{
            DRAW_RECTANGLE,
            (byte) x, (byte) y,
            (byte) x2, (byte) y2,
            (byte) r, (byte) g, (byte) b,
            (byte) r, (byte) g, (byte) b
        });
    }

    /**
     * Hardware-accelerated window copy.
     *
     * @param x1 Source left X.
     * @param y1 Source top Y.
     * @param x2 Source right X.
     * @param y2 Source bottom Y.
     * @param dx Destination left X.
     * @param dy Destination top Y.
     */
    public final void copy(final int x1, final int y1, final int x2, final int y2,
            final int dx, final int dy) {
        writeCommand(new byte[]{
            COPY_WINDOW,
            (byte) x1, (byte) y1,
            (byte) x2, (byte) y2,
            (byte) dx, (byte) dy
        });
    }

    /**
     * Configures and starts hardware scrolling.
     *
     * @param horizontal How many columns to shift per step.
     * @param startRow Starting row address.
     * @param rowCount Number of rows to scroll.
     * @param vertical How many rows to shift per step.
     * @param interval Frame interval between steps.
     */
    public final void setupScroll(final int horizontal, final int startRow,
            final int rowCount, final int vertical, final int interval) {
        writeCommand(new byte[]{DEACTIVATE_SCROLLING});
        writeCommand(new byte[]{
            SET_SCROLLING,
            (byte) horizontal, (byte) startRow,
            (byte) rowCount, (byte) vertical, (byte) interval
        });
        writeCommand(new byte[]{ACTIVATE_SCROLLING});
    }

    /**
     * Stops hardware scrolling.
     */
    public final void stopScroll() {
        writeCommand(new byte[]{DEACTIVATE_SCROLLING});
    }

    /**
     * Maps a {@link BufferedImage} to RGB565 and sends to display using zero-allocation conversion.
     *
     * @param image BufferedImage to render.
     */
    @Override
    public final void drawImage(final BufferedImage image) {
        writeCommand(new byte[]{SET_COLUMN_ADDRESS, (byte) 0, (byte) (getWidth() - 1)});
        writeCommand(new byte[]{SET_ROW_ADDRESS, (byte) 0, (byte) (getHeight() - 1)});

        packRgb888ToRgb565(image);

        writeData(getImageSegment());
        writeCommand(new byte[]{NO_OP});
    }

    /**
     * Maps a sub-region of a {@link BufferedImage} to RGB565 and renders it to a specific window on the display (dirty write).
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
        writeCommand(new byte[]{NO_OP});
    }

    /**
     * Sets the active drawing window on the SSD1331 display controller.
     *
     * @param x X start coordinate.
     * @param y Y start coordinate.
     * @param width Window width.
     * @param height Window height.
     */
    @Override
    public final void setWindow(final int x, final int y, final int width, final int height) {
        writeCommand(new byte[]{
            SET_COLUMN_ADDRESS,
            (byte) x,
            (byte) (x + width - 1),
            SET_ROW_ADDRESS,
            (byte) y,
            (byte) (y + height - 1)
        });
    }

    /**
     * Template implementation called by AbstractDevice. Blanks the display completely and turns off the power gate before closing
     * the native bindings.
     */
    @Override
    protected void closeNative() {
        log.debug("Closing SSD1331 OLED Display");
        try {
            if (getHandle().address() != 0 && getArena().scope().isAlive()) {
                writeCommand(new byte[]{DISPLAY_OFF});
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
            if (resHandle.address() != 0) {
                Periphery.gpio_close(resHandle);
            }
        }
    }
}
