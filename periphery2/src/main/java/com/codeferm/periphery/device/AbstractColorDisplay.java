/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.periphery.gpio_handle;
import org.periphery.spi_handle;

/**
 * Abstract base class for color display modules using Java Foreign Function & Memory (FFM) API.
 * <p>
 * Provides shared capabilities for color displays, including dimensions, buffer chunking strategies, high-performance color
 * zero-allocation conversion logic, hardware-agnostic graphics primitives, and display rotation management.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public abstract class AbstractColorDisplay extends AbstractDevice {

    /**
     * libperiphery constant for output direction (1 = GPIO_DIR_OUT).
     */
    protected static final int GPIO_DIR_OUT = 1;

    /**
     * Physical base display width in pixels (portrait mode).
     */
    protected final int baseWidth;

    /**
     * Physical base display height in pixels (portrait mode).
     */
    protected final int baseHeight;

    /**
     * Current effective display width in pixels (accounts for rotation).
     */
    @Getter
    private int width;

    /**
     * Current effective display height in pixels (accounts for rotation).
     */
    @Getter
    private int height;

    /**
     * Current display rotation angle (0, 90, 180, 270).
     */
    @Getter
    protected int rotation = 0;

    /**
     * Transfer chunk buffer size in bytes.
     */
    protected final int bufferSize;

    /**
     * Bytes per pixel (2 for RGB565, 3 for RGB666).
     */
    protected final int bytesPerPixel;

    /**
     * Native handle for the Data/Command GPIO pin.
     */
    @Getter
    protected final MemorySegment dcHandle;

    /**
     * Reusable native segment for SPI commands to prevent heap thrashing.
     */
    @Getter
    protected final MemorySegment commandSegment;

    /**
     * Reusable native segment for full-frame image data.
     */
    @Getter
    protected MemorySegment imageSegment;

    /**
     * Initializes the abstract color display with dimensions and default chunk buffer size (65536 bytes, 2 bytes/pixel).
     *
     * @param width Display width in pixels.
     * @param height Display height in pixels.
     */
    protected AbstractColorDisplay(final int width, final int height) {
        this(width, height, 65536, 2);
    }

    /**
     * Initializes the abstract color display with dimensions and a custom buffer size (2 bytes/pixel).
     *
     * @param width Display width in pixels.
     * @param height Display height in pixels.
     * @param bufferSize Transfer buffer chunk size in bytes.
     */
    protected AbstractColorDisplay(final int width, final int height, final int bufferSize) {
        this(width, height, bufferSize, 2);
    }

    /**
     * Initializes the abstract color display with dimensions, custom buffer size, and bytes per pixel.
     *
     * @param width Display width in pixels.
     * @param height Display height in pixels.
     * @param bufferSize Transfer buffer chunk size in bytes.
     * @param bytesPerPixel Bytes per pixel (2 or 3).
     */
    protected AbstractColorDisplay(final int width, final int height, final int bufferSize, final int bytesPerPixel) {
        super(spi_handle.layout());
        this.baseWidth = width;
        this.baseHeight = height;
        this.width = width;
        this.height = height;
        this.bufferSize = bufferSize;
        this.bytesPerPixel = bytesPerPixel;
        this.dcHandle = getArena().allocate(gpio_handle.layout());
        this.commandSegment = getArena().allocate(64);
        this.imageSegment = getArena().allocate((long) width * height * bytesPerPixel);
    }

    /**
     * Sets the display rotation orientation, adjusting effective width, height, and resizing the frame buffer segment accordingly.
     *
     * @param rotation Rotation angle (0, 90, 180, 270).
     */
    public void setRotation(final int rotation) {
        final var normalized = Math.floorMod(rotation, 360);
        if (normalized != 0 && normalized != 90 && normalized != 180 && normalized != 270) {
            throw new IllegalArgumentException("Rotation must be 0, 90, 180, or 270 degrees: " + rotation);
        }
        this.rotation = normalized;
        if (normalized == 90 || normalized == 270) {
            this.width = baseHeight;
            this.height = baseWidth;
        } else {
            this.width = baseWidth;
            this.height = baseHeight;
        }
        // Reallocate native image segment to match new rotated dimensions
        this.imageSegment = getArena().allocate((long) this.width * this.height * bytesPerPixel);
    }

    /**
     * Sends command bytes to the display controller.
     *
     * @param data Command array with optional parameter bytes.
     */
    public abstract void writeCommand(final byte[] data);

    /**
     * Sends pixel data bytes from a native {@link MemorySegment}.
     *
     * @param segment Native segment containing pixel data.
     */
    public abstract void writeData(final MemorySegment segment);

    /**
     * Clears the entire display.
     */
    public abstract void clear();

    /**
     * Maps a {@link BufferedImage} and renders it to the display.
     *
     * @param image BufferedImage to render.
     */
    public abstract void drawImage(final BufferedImage image);

    /**
     * Maps a sub-region of a {@link BufferedImage} and renders it to a specific window on the display.
     *
     * @param image Source BufferedImage.
     * @param x Destination window X start coordinate.
     * @param y Destination window Y start coordinate.
     * @param width Window width.
     * @param height Window height.
     */
    public void drawImage(final BufferedImage image, final int x, final int y, final int width, final int height) {
        setWindow(x, y, width, height);
        final var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        final var imgWidth = image.getWidth();

        var destOffset = 0L;
        for (var sy = 0; sy < height; sy++) {
            for (var sx = 0; sx < width; sx++) {
                final var p = pixels[sy * imgWidth + sx];
                if (bytesPerPixel == 3) {
                    imageSegment.set(ValueLayout.JAVA_BYTE, destOffset, (byte) ((p >> 16) & 0xFF));
                    imageSegment.set(ValueLayout.JAVA_BYTE, destOffset + 1L, (byte) ((p >> 8) & 0xFF));
                    imageSegment.set(ValueLayout.JAVA_BYTE, destOffset + 2L, (byte) (p & 0xFF));
                    destOffset += 3L;
                } else {
                    final var r = (p >> 16) & 0xFF;
                    final var g = (p >> 8) & 0xFF;
                    final var b = p & 0xFF;
                    final var rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);

                    imageSegment.set(ValueLayout.JAVA_BYTE, destOffset, (byte) (rgb565 >> 8));
                    imageSegment.set(ValueLayout.JAVA_BYTE, destOffset + 1L, (byte) rgb565);
                    destOffset += 2L;
                }
            }
        }
        writeData(imageSegment.asSlice(0, (long) width * height * bytesPerPixel));
    }

    /**
     * Sets the active drawing window on the display controller.
     *
     * @param x X start coordinate.
     * @param y Y start coordinate.
     * @param width Window width.
     * @param height Window height.
     */
    public abstract void setWindow(final int x, final int y, final int width, final int height);

    /**
     * Draws a single pixel at the specified coordinates.
     *
     * @param x X coordinate.
     * @param y Y coordinate.
     * @param color RGB color integer.
     */
    public abstract void drawPixel(final int x, final int y, final int color);

    /**
     * Draws a line from (x0, y0) to (x1, y1) using Bresenham's algorithm.
     *
     * @param x0 Start X coordinate.
     * @param y0 Start Y coordinate.
     * @param x1 End X coordinate.
     * @param y1 End Y coordinate.
     * @param color RGB color integer.
     */
    public void drawLine(final int x0, final int y0, final int x1, final int y1, final int color) {
        final var dx = Math.abs(x1 - x0);
        final var dy = Math.abs(y1 - y0);
        final var sx = x0 < x1 ? 1 : -1;
        final var sy = y0 < y1 ? 1 : -1;
        var err = dx - dy;

        var cx = x0;
        var cy = y0;

        while (true) {
            drawPixel(cx, cy, color);
            if (cx == x1 && cy == y1) {
                break;
            }
            final var e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                cx += sx;
            }
            if (e2 < dx) {
                err += dx;
                cy += sy;
            }
        }
    }

    /**
     * Draws an unfilled rectangle.
     *
     * @param x Top-left X coordinate.
     * @param y Top-left Y coordinate.
     * @param width Rectangle width.
     * @param height Rectangle height.
     * @param color RGB color integer.
     */
    public void drawRect(final int x, final int y, final int width, final int height, final int color) {
        final var x1 = x + width - 1;
        final var y1 = y + height - 1;
        drawLine(x, y, x1, y, color);
        drawLine(x1, y, x1, y1, color);
        drawLine(x1, y1, x, y1, color);
        drawLine(x, y1, x, y, color);
    }

    /**
     * Draws a filled rectangle.
     *
     * @param x Top-left X coordinate.
     * @param y Top-left Y coordinate.
     * @param width Rectangle width.
     * @param height Rectangle height.
     * @param color RGB color integer.
     */
    public void fillRect(final int x, final int y, final int width, final int height, final int color) {
        final var endX = x + width;
        final var endY = y + height;
        for (var cy = y; cy < endY; cy++) {
            for (var cx = x; cx < endX; cx++) {
                drawPixel(cx, cy, color);
            }
        }
    }

    /**
     * Zero-allocation conversion method mapping an RGB888 {@link BufferedImage} directly into the pre-allocated
     * {@link #imageSegment} in RGB565 Big-Endian format.
     *
     * @param image Source BufferedImage.
     */
    protected final void packRgb888ToRgb565(final BufferedImage image) {
        final var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (var i = 0; i < pixels.length; i++) {
            final var p = pixels[i];
            final var r = (p >> 16) & 0xFF;
            final var g = (p >> 8) & 0xFF;
            final var b = p & 0xFF;
            final var rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);
            final var offset = i * 2L;
            imageSegment.set(ValueLayout.JAVA_BYTE, offset, (byte) (rgb565 >> 8));
            imageSegment.set(ValueLayout.JAVA_BYTE, offset + 1L, (byte) rgb565);
        }
    }

    /**
     * Zero-allocation conversion method mapping an RGB888 {@link BufferedImage} directly into the pre-allocated
     * {@link #imageSegment} in RGB666 format.
     *
     * @param image Source BufferedImage.
     */
    protected final void packRgb888ToRgb666(final BufferedImage image) {
        final var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (var i = 0; i < pixels.length; i++) {
            final var p = pixels[i];
            final var offset = i * 3L;
            imageSegment.set(ValueLayout.JAVA_BYTE, offset, (byte) ((p >> 16) & 0xFF));
            imageSegment.set(ValueLayout.JAVA_BYTE, offset + 1L, (byte) ((p >> 8) & 0xFF));
            imageSegment.set(ValueLayout.JAVA_BYTE, offset + 2L, (byte) (p & 0xFF));
        }
    }
}
