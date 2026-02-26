/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;
import org.periphery.spi_handle;

/**
 * SSD1331 96x64 RGB OLED driver using Java Foreign Function & Memory (FFM) API.
 * <p>
 * This driver provides a high-performance interface to the SSD1331 controller, utilizing {@link MemorySegment} for zero-copy data
 * transfers and supporting all Hardware Graphic Acceleration Commands (GAC).
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class Ssd1331 implements AutoCloseable {

    /**
     * libperiphery constant for output direction (1 = GPIO_DIR_OUT).
     */
    private static final int GPIO_DIR_OUT = 1;

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
     * Shared arena for native memory segment management.
     */
    @Getter
    private final Arena arena;

    /**
     * Native handle for the SPI bus.
     */
    private final MemorySegment spiHandle;

    /**
     * Native handle for the Data/Command GPIO pin.
     */
    private final MemorySegment dcHandle;

    /**
     * Native handle for the Hardware Reset GPIO pin.
     */
    private final MemorySegment resHandle;

    /**
     * SSD1331 display width in pixels.
     */
    @Getter
    private final int width = 96;

    /**
     * SSD1331 display height in pixels.
     */
    @Getter
    private final int height = 64;

    /**
     * Initialize hardware with SPI and GPIO handles via FFM.
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
        this.arena = Arena.ofShared();
        this.spiHandle = arena.allocate(spi_handle.layout());
        this.dcHandle = arena.allocate(gpio_handle.layout());
        this.resHandle = arena.allocate(gpio_handle.layout());

        final var cDevice = arena.allocateFrom(device);
        final var cGpioDev = arena.allocateFrom(gpioDevice);

        if (Periphery.spi_open(spiHandle, cDevice, mode, speed) < 0) {
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
     * Sends command bytes to the controller (DC pin LOW).
     *
     * @param data Command array.
     */
    public final void writeCommand(final byte[] data) {
        Periphery.gpio_write(dcHandle, false);
        final var cData = arena.allocateFrom(ValueLayout.JAVA_BYTE, data);
        if (Periphery.spi_transfer(spiHandle, cData, cData, data.length) < 0) {
            throw new RuntimeException("SPI Command failed");
        }
    }

    /**
     * Sends data bytes (pixels) directly from a {@link MemorySegment} (DC pin HIGH).
     *
     * @param segment Native segment containing pixel data.
     */
    public final void writeData(final MemorySegment segment) {
        Periphery.gpio_write(dcHandle, true);
        if (Periphery.spi_transfer(spiHandle, segment, segment, segment.byteSize()) < 0) {
            throw new RuntimeException("SPI Data Segment transfer failed");
        }
    }

    /**
     * Sends data bytes (pixels) from a Java array (DC pin HIGH).
     *
     * @param data Pixel data array.
     */
    public final void writeData(final byte[] data) {
        final var cData = arena.allocateFrom(ValueLayout.JAVA_BYTE, data);
        writeData(cData);
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
            writeCommand(new byte[]{SET_REMAP, (byte) 0x72});
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
    public final void clear() {
        writeCommand(new byte[]{CLEAR_WINDOW, (byte) 0, (byte) 0, (byte) (width - 1), (byte) (height - 1)});
    }

    /**
     * Hardware accelerated line drawing.
     *
     * @param x1 Start X.
     * @param y1 Start Y.
     * @param x2 End X.
     * @param y2 End Y.
     * @param r Red (0-63).
     * @param g Green (0-63).
     * @param b Blue (0-63).
     */
    public final void drawLine(final int x1, final int y1, final int x2, final int y2,
            final int r, final int g, final int b) {
        writeCommand(new byte[]{DRAW_LINE, (byte) x1, (byte) y1, (byte) x2, (byte) y2,
            (byte) r, (byte) g, (byte) b});
    }

    /**
     * Hardware accelerated rectangle drawing.
     *
     * @param x1 Left X.
     * @param y1 Top Y.
     * @param x2 Right X.
     * @param y2 Bottom Y.
     * @param r Red (0-63).
     * @param g Green (0-63).
     * @param b Blue (0-63).
     * @param fill True to fill rectangle.
     */
    public final void drawRect(final int x1, final int y1, final int x2, final int y2,
            final int r, final int g, final int b, final boolean fill) {
        writeCommand(new byte[]{FILL_ENABLE, fill ? (byte) 0x01 : (byte) 0x00});
        writeCommand(new byte[]{DRAW_RECTANGLE, (byte) x1, (byte) y1, (byte) x2, (byte) y2,
            (byte) r, (byte) g, (byte) b, (byte) r, (byte) g, (byte) b});
    }

    /**
     * Hardware accelerated window copy.
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
        writeCommand(new byte[]{COPY_WINDOW, (byte) x1, (byte) y1, (byte) x2, (byte) y2,
            (byte) dx, (byte) dy});
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
        writeCommand(new byte[]{SET_SCROLLING, (byte) horizontal, (byte) startRow,
            (byte) rowCount, (byte) vertical, (byte) interval});
        writeCommand(new byte[]{ACTIVATE_SCROLLING});
    }

    /**
     * Stops hardware scrolling.
     */
    public final void stopScroll() {
        writeCommand(new byte[]{DEACTIVATE_SCROLLING});
    }

    /**
     * Maps a {@link BufferedImage} to RGB565 and sends to display.
     *
     * @param image BufferedImage to render.
     */
    public final void drawImage(final BufferedImage image) {
        writeCommand(new byte[]{SET_COLUMN_ADDRESS, (byte) 0, (byte) (width - 1)});
        writeCommand(new byte[]{SET_ROW_ADDRESS, (byte) 0, (byte) (height - 1)});
        final var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        final var output = new byte[pixels.length * 2];
        for (var i = 0; i < pixels.length; i++) {
            final var p = pixels[i];
            final var packed = (((p >> 19) & 0x1F) << 11) | (((p >> 10) & 0x3F) << 5) | ((p >> 3) & 0x1F);
            output[i * 2] = (byte) (packed >> 8);
            output[i * 2 + 1] = (byte) (packed & 0xFF);
        }
        writeData(output);
        writeCommand(new byte[]{NO_OP});
    }

    /**
     * Safely closes native resources.
     */
    @Override
    public final void close() {
        try (arena) {
            writeCommand(new byte[]{DISPLAY_OFF});
            Periphery.spi_close(spiHandle);
            Periphery.gpio_close(dcHandle);
            Periphery.gpio_close(resHandle);
        }
    }
}
