/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * High-performance, zero-allocation Defender-style scroller optimized for small color displays (like SSD1331) featuring smooth
 * cosine-interpolated rolling terrain.
 *
 * @author Steven P. Goldsmith
 * @version 1.9.0
 * @since 1.0.0
 */
@Slf4j
@Command(
        name = "DefenderScroller",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "Bi-directional terrain scroller with smooth cosine-interpolated terrain."
)
public class DefenderScroller extends Base {

    /**
     * Bytes per pixel (RGB565).
     */
    private static final int BYTES_PER_PIXEL = 2;

    /**
     * Back Buffer (render target).
     */
    private byte[] backBuffer;

    private int displayWidth;
    private int displayHeight;

    /**
     * Random number generator for terrain generation and direction logic.
     */
    private final Random random = new Random();
    /**
     * Terrain height map.
     */
    private int[] terrain;
    private int terrainWidth;
    private int viewOffset = 0;

    /**
     * Scroll direction: -1 for Left (Ship faces Left), 1 for Right (Ship faces Right).
     */
    private int velocity = -1;

    /**
     * Fixed horizontal anchor point for the ship.
     */
    private int shipX;
    /**
     * Vertical position of the ship in sub-pixel coordinates.
     */
    private double shipY = 25.0;
    /**
     * Desired distance between the ship and the peak of the hills.
     */
    private int hoverHeight = 12;
    /**
     * Smoothing factor for vertical tracking (0.15 = 15% move toward target per frame).
     */
    private final double lerpFactor = 0.15;

    /**
     * Sets a pixel in the back buffer using RGB565 color mapping.
     *
     * @param x Screen X coordinate.
     * @param y Screen Y coordinate.
     * @param r Red component (0-31).
     * @param g Green component (0-63).
     * @param b Blue component (0-31).
     */
    private void setPixel(final int x, final int y, final int r, final int g, final int b) {
        if (x < 0 || x >= displayWidth || y < 0 || y >= displayHeight) {
            return;
        }
        final var idx = (y * displayWidth + x) * BYTES_PER_PIXEL;
        final var color = ((r & 0x1F) << 11) | ((g & 0x3F) << 5) | (b & 0x1F);
        backBuffer[idx] = (byte) (color >> 8);
        backBuffer[idx + 1] = (byte) (color & 0xFF);
    }

    /**
     * Draws a line into the back buffer using Bresenham's algorithm.
     *
     * @param x0 Start X.
     * @param y0 Start Y.
     * @param x1 End X.
     * @param y1 End Y.
     * @param r Red component (0-31).
     * @param g Green component (0-63).
     * @param b Blue component (0-31).
     */
    private void drawLine(int x0, int y0, final int x1, final int y1, final int r, final int g, final int b) {
        final var dx = Math.abs(x1 - x0);
        final var dy = Math.abs(y1 - y0);
        final var sx = x0 < x1 ? 1 : -1;
        final var sy = y0 < y1 ? 1 : -1;
        var err = dx - dy;

        while (true) {
            setPixel(x0, y0, r, g, b);
            if (x0 == x1 && y0 == y1) {
                break;
            }
            final var e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    /**
     * Initializes terrain using cosine interpolation over random control points to ensure continuous, gradual, and varied rolling
     * hills without flat shelves.
     *
     * @param width Screen width.
     * @param height Screen height.
     */
    private void initTerrain(final int width, final int height) {
        terrainWidth = width * 4; // 4 screens wide for seamless looping
        terrain = new int[terrainWidth];

        final var sampleStep = Math.max(12, width / 6); // Distance between random control nodes
        final var numPoints = (terrainWidth / sampleStep) + 2;
        final var controlPoints = new int[numPoints];

        final var minHeight = height / 5;
        final var maxHeight = (height * 3) / 5;

        // Generate random control heights
        for (var i = 0; i < numPoints; i++) {
            controlPoints[i] = minHeight + random.nextInt(maxHeight - minHeight + 1);
        }

        // Interpolate smoothly between control points using cosine interpolation
        for (var i = 0; i < terrainWidth; i++) {
            final var segmentIndex = i / sampleStep;
            final var frac = (double) (i % sampleStep) / sampleStep;
            final var mu2 = (1.0 - Math.cos(frac * Math.PI)) / 2.0;

            final var y1 = controlPoints[segmentIndex];
            final var y2 = controlPoints[segmentIndex + 1];

            terrain[i] = (int) (y1 * (1.0 - mu2) + y2 * mu2);
        }
    }

    /**
     * Renders a single vertical slice of the world onto a specific screen X coordinate in the back buffer.
     *
     * @param screenX The screen X column to draw.
     */
    private void drawColumn(final int screenX) {
        if (screenX < 0 || screenX >= displayWidth) {
            return;
        }
        var worldX = (viewOffset + screenX) % terrainWidth;
        if (worldX < 0) {
            worldX += terrainWidth;
        }

        final var hillHeight = terrain[worldX];
        final var hillTopY = (displayHeight - 1) - hillHeight;

        // 1. Black Sky
        if (hillTopY > 0) {
            drawLine(screenX, 0, screenX, hillTopY - 1, 0, 0, 0);
        }
        // 2. Green Hill (from just below peak down to bottom)
        if (hillTopY + 1 < displayHeight) {
            drawLine(screenX, hillTopY + 1, screenX, displayHeight - 1, 0, 45, 0);
        }
        // 3. Guaranteed Ridge Peak Point (Bright white/yellow top pixel)
        setPixel(screenX, hillTopY, 31, 63, 31);
    }

    /**
     * Draws the ship sprite and its flickering engine flame into the back buffer.
     *
     * @param x Horizontal center.
     * @param y Vertical center.
     * @param dir Direction (-1 for left, 1 for right).
     */
    private void drawShip(final int x, final int y, final int dir) {
        final var r = 0;
        final var g = 63;
        final var b = 63; // Cyan ship

        if (dir < 0) {
            // Facing Left
            drawLine(x + 5, y - 2, x - 5, y, r, g, b);
            drawLine(x - 5, y, x + 5, y + 2, r, g, b);
            drawLine(x + 5, y + 2, x + 5, y - 2, r, g, b);
            if (random.nextBoolean()) {
                drawLine(x + 6, y, x + 10, y, 63, 15, 0); // Flame on right
            }
        } else {
            // Facing Right
            drawLine(x - 5, y - 2, x + 5, y, r, g, b);
            drawLine(x + 5, y, x - 5, y + 2, r, g, b);
            drawLine(x - 5, y + 2, x - 5, y - 2, r, g, b);
            if (random.nextBoolean()) {
                drawLine(x - 6, y, x - 10, y, 63, 15, 0); // Flame on left
            }
        }
    }

    /**
     * Primary loop for the demonstration.
     *
     * @param display The hardware color display device instance.
     * @throws Exception If communication fails.
     */
    public final void runDemo(final AbstractColorDisplay display) throws Exception {
        log.info("Starting Defender Scroller with Cosine-Interpolated Terrain...");

        displayWidth = display.getWidth();
        displayHeight = display.getHeight();

        hoverHeight = Math.max(8, displayHeight / 5);

        final var bufferSize = displayWidth * displayHeight * BYTES_PER_PIXEL;
        backBuffer = new byte[bufferSize];

        Arrays.fill(backBuffer, (byte) 0);

        shipX = displayWidth / 2;
        initTerrain(displayWidth, displayHeight);
        shipY = displayHeight / 2.0;
        viewOffset = 0;

        final var backSeg = MemorySegment.ofArray(backBuffer);

        while (isRunning() && !Thread.currentThread().isInterrupted()) {
            final var startTime = System.currentTimeMillis();

            // 1. Update background viewport offset for smooth scrolling
            viewOffset += velocity;
            if (viewOffset < 0) {
                viewOffset += terrainWidth;
            } else if (viewOffset >= terrainWidth) {
                viewOffset %= terrainWidth;
            }

            // 2. Redraw entire background terrain columns into back buffer
            for (var x = 0; x < displayWidth; x++) {
                drawColumn(x);
            }

            // 3. Terrain following physics (Lerp)
            var shipWorldX = (viewOffset + shipX) % terrainWidth;
            if (shipWorldX < 0) {
                shipWorldX += terrainWidth;
            }
            final var targetAltitudeY = (displayHeight - 1) - terrain[shipWorldX] - hoverHeight;
            shipY += (targetAltitudeY - shipY) * lerpFactor;
            final var currentShipScreenY = (int) shipY;

            // 4. Render ship into back buffer at current position
            drawShip(shipX, currentShipScreenY, velocity);

            // 5. Randomized direction change
            if (random.nextInt(400) == 0) {
                velocity *= -1;
            }

            // 6. Blast full screen buffer efficiently to hardware
            display.setWindow(0, 0, displayWidth, displayHeight);
            MemorySegment.copy(backSeg, ValueLayout.JAVA_BYTE, 0, display.getImageSegment(), ValueLayout.JAVA_BYTE, 0, bufferSize);
            display.writeData(display.getImageSegment());

            // 7. Frame rate throttling
            final var elapsedTime = System.currentTimeMillis() - startTime;
            final var targetDelay = 1000 / getFps();

            if (elapsedTime < targetDelay) {
                try {
                    TimeUnit.MILLISECONDS.sleep(targetDelay - elapsedTime);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.debug("Interrupted during sleep, exiting loop.");
                    break;
                }
            }
        }
    }

    /**
     * Command entry point.
     *
     * @return Process exit code.
     * @throws Exception If an error occurs during runtime.
     */
    @Override
    public final Integer call() throws Exception {
        super.call();
        try {
            runDemo((AbstractColorDisplay) getDisplay());
        } finally {
            done();
        }
        return 0;
    }

    /**
     * Main method.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new DefenderScroller()).execute(args));
    }
}
