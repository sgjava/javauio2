/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * High-performance, flicker-free 3D Boing Ball recreation supporting multiple displays.
 * <p>
 * Implements a software-buffered rendering pipeline adaptable to any display dimensions, automatically scaling the ball, grid, and
 * sending frames via FFM memory segments without controller-specific checks.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(
        name = "Boing",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "Full-throttle Buffered 3D Boing Ball."
)
public class Boing extends Base {

    /**
     * Bytes per pixel (RGB565).
     */
    private static final int BYTES_PER_PIXEL = 2;

    /**
     * Software Framebuffer (dynamically sized based on display).
     */
    private byte[] frameBuffer;

    private int displayWidth;
    private int displayHeight;
    private int radius;

    private double ballX;
    private double ballY;
    private double velX = 1.4;
    private double velY = 0.0;
    private final double gravity = 0.12;
    private final double bounce = -0.88;
    private double phase = 0.0;

    /**
     * Initializes the background grid directly into the software buffer.
     */
    private void renderGrid() {
        Arrays.fill(frameBuffer, (byte) 0);
        // RGB565: Dark Gray (2, 4, 2)
        final var high = (byte) 0x10;
        final var low = (byte) 0x82;

        for (var y = 0; y < displayHeight; y++) {
            for (var x = 0; x < displayWidth; x++) {
                if (x % 16 == 0 || y % 16 == 0) {
                    final var idx = (y * displayWidth + x) * BYTES_PER_PIXEL;
                    frameBuffer[idx] = high;
                    frameBuffer[idx + 1] = low;
                }
            }
        }
    }

    /**
     * Sets a pixel in the local buffer using RGB565 color mapping.
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
        frameBuffer[idx] = (byte) (color >> 8);
        frameBuffer[idx + 1] = (byte) (color & 0xFF);
    }

    /**
     * Renders a circular black shadow into the software buffer.
     *
     * @param x Center X of the ball.
     * @param y Center Y of the ball.
     */
    private void renderShadow(final int x, final int y) {
        final var shadowOffset = 4;
        final var sx = x + shadowOffset;
        final var sy = y + shadowOffset;
        final var r2 = radius * radius;
        for (var iy = -radius; iy <= radius; iy++) {
            final var hWidth = (int) Math.sqrt(r2 - (iy * iy));
            for (var ix = sx - hWidth; ix <= sx + hWidth; ix++) {
                setPixel(ix, sy + iy, 0, 0, 0);
            }
        }
    }

    /**
     * Renders a 3D convex checkered ball using spherical coordinate mapping.
     *
     * @param x Center X coordinate.
     * @param y Center Y coordinate.
     * @param rot Rotation phase in radians.
     */
    private void renderBall(final int x, final int y, final double rot) {
        final var r2 = (double) radius * radius;
        for (var iy = -radius; iy < radius; iy++) {
            final var hWidth = Math.sqrt(r2 - (double) (iy * iy));
            final var startX = (int) (x - hWidth);
            final var endX = (int) (x + hWidth);
            final var lat = Math.asin(iy / (double) radius);
            for (var ix = startX; ix <= endX; ix++) {
                final var dx = ix - x;
                final var lon = Math.asin(Math.clamp(dx / hWidth, -1.0, 1.0));
                final var xSec = (int) Math.floor((lon + rot) / (Math.PI / 4.0)) % 2;
                final var ySec = (int) Math.floor((lat + (Math.PI / 2.0)) / (Math.PI / 4.0)) % 2;
                final var isRed = (Math.abs(xSec) == Math.abs(ySec));
                if (isRed) {
                    setPixel(ix, y + iy, 31, 0, 0); // Red
                } else {
                    setPixel(ix, y + iy, 31, 63, 31); // White
                }
            }
        }
    }

    /**
     * Main execution loop for the Boing demo.
     *
     * @param display The unified display driver instance.
     * @throws Exception If hardware communication fails.
     */
    public final void runDemo(final AbstractColorDisplay display) throws Exception {
        log.info("Starting Boing Demo...");

        displayWidth = display.getWidth();
        displayHeight = display.getHeight();
        final var ballSize = Math.min(displayWidth, displayHeight) / 4;
        radius = ballSize / 2;

        frameBuffer = new byte[displayWidth * displayHeight * BYTES_PER_PIXEL];
        ballX = displayWidth / 2.0;
        ballY = displayHeight / 4.0;

        while (!Thread.currentThread().isInterrupted() && isRunning()) {
            final var startTime = System.currentTimeMillis();

            // 1. Scene Assembly (RAM only)
            renderGrid();
            renderShadow((int) ballX, (int) ballY);
            renderBall((int) ballX, (int) ballY, phase);

            // 2. Physics Update
            ballX += velX;
            velY += gravity;
            ballY += velY;
            if (ballX < radius || ballX > (displayWidth - 1) - radius) {
                velX *= -1;
            }
            if (ballY > (displayHeight - 1) - radius) {
                ballY = (displayHeight - 1) - radius;
                velY *= bounce;
                if (Math.abs(velY) < 1.0) {
                    velY = -2.8;
                }
            }
            phase += (velX * 0.12);

            // 3. Controller-Agnostic Display Update
            display.setWindow(0, 0, displayWidth, displayHeight);

            MemorySegment.copy(frameBuffer, 0, display.getImageSegment(), ValueLayout.JAVA_BYTE, 0, frameBuffer.length);
            display.writeData(display.getImageSegment());

            final var elapsedTime = System.currentTimeMillis() - startTime;
            try {
                if (elapsedTime < 16) {
                    TimeUnit.MILLISECONDS.sleep(16 - elapsedTime);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Boing demo interrupted, exiting render loop.");
                break;
            }
        }
    }

    /**
     * Entry point for the Command Line Interface.
     *
     * @return Exit code.
     * @throws Exception on execution failure.
     */
    @Override
    public final Integer call() throws Exception {
        super.call();
        try {
            runDemo(getDisplay());
        } finally {
            done();
        }
        return 0;
    }

    /**
     * Application entry point.
     *
     * @param args Command line arguments.
     */
    public static void main(final String[] args) {
        System.exit(new CommandLine(new Boing()).execute(args));
    }
}
