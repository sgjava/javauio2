/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.ssd1331.demo;

import com.codeferm.periphery.device.Ssd1331;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * High-performance, flicker-free Amiga Boing Ball recreation for SSD1331.
 * <p>
 * Implements a software-buffered rendering pipeline to eliminate artifacts and blinking. The entire 96x64 frame is assembled in a
 * local RGB565 buffer and transmitted via a single atomic SPI transaction.
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
        version = "3.2.0",
        description = "Full-throttle Buffered 3D Boing Ball."
)
public class Boing extends Base {

    /**
     * Display width in pixels.
     */
    private static final int WIDTH = 96;
    /**
     * Display height in pixels.
     */
    private static final int HEIGHT = 64;
    /**
     * Ball diameter in pixels.
     */
    private static final int BALL_SIZE = 22;
    /**
     * Ball radius for calculation.
     */
    private static final int RADIUS = BALL_SIZE / 2;
    /**
     * Bytes per pixel (RGB565).
     */
    private static final int BYTES_PER_PIXEL = 2;

    /**
     * * Software Framebuffer (12,288 bytes). Well within the 64K SPI buffer to allow a single atomic write.
     */
    private final byte[] frameBuffer = new byte[WIDTH * HEIGHT * BYTES_PER_PIXEL];

    /**
     * Horizontal position.
     */
    private double ballX = 48.0;
    /**
     * Vertical position.
     */
    private double ballY = 20.0;
    /**
     * Horizontal velocity.
     */
    private double velX = 1.4;
    /**
     * Vertical velocity.
     */
    private double velY = 0.0;
    /**
     * Gravity constant.
     */
    private final double gravity = 0.12;
    /**
     * Elasticity/Bounce coefficient.
     */
    private final double bounce = -0.88;
    /**
     * Rotation phase in radians.
     */
    private double phase = 0.0;

    /**
     * Initializes the background grid directly into the software buffer. Uses RGB565 format (5 bits Red, 6 bits Green, 5 bits
     * Blue).
     */
    private void renderGrid() {
        Arrays.fill(frameBuffer, (byte) 0);
        // RGB565: Dark Gray (2, 4, 2)
        final var high = (byte) 0x10;
        final var low = (byte) 0x82;

        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                if (x % 16 == 0 || y % 16 == 0) {
                    final var idx = (y * WIDTH + x) * BYTES_PER_PIXEL;
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
        if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) {
            return;
        }
        final var idx = (y * WIDTH + x) * BYTES_PER_PIXEL;
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
        final var r2 = RADIUS * RADIUS;
        for (var iy = -RADIUS; iy <= RADIUS; iy++) {
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
        final var r2 = (double) RADIUS * RADIUS;
        for (var iy = -RADIUS; iy < RADIUS; iy++) {
            final var hWidth = Math.sqrt(r2 - (double) (iy * iy));
            final var startX = (int) (x - hWidth);
            final var endX = (int) (x + hWidth);
            final var lat = Math.asin(iy / (double) RADIUS);
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
     * @param oled The SSD1331 hardware driver.
     * @throws Exception If hardware communication fails.
     */
    public final void runDemo(final Ssd1331 oled) throws Exception {
        log.info("Starting Boing Demo...");
        oled.setup();
        // Hardware Remap: 16-bit RGB565 mode
        oled.writeCommand(new byte[]{(byte) 0xA0, (byte) 0x72});
        while (!Thread.currentThread().isInterrupted()) {
            final var startTime = System.currentTimeMillis();
            // 1. Scene Assembly (RAM only)
            renderGrid();
            renderShadow((int) ballX, (int) ballY);
            renderBall((int) ballX, (int) ballY, phase);
            // 2. Physics Update
            ballX += velX;
            velY += gravity;
            ballY += velY;
            if (ballX < RADIUS || ballX > (WIDTH - 1) - RADIUS) {
                velX *= -1;
            }
            if (ballY > (HEIGHT - 1) - RADIUS) {
                ballY = (HEIGHT - 1) - RADIUS;
                velY *= bounce;
                if (Math.abs(velY) < 1.0) {
                    velY = -2.8;
                }
            }
            phase += (velX * 0.12);
            // 3. One-Shot SPI Blast
            // Define write window: Column 0-95, Row 0-63
            oled.writeCommand(new byte[]{(byte) 0x15, 0, 95, (byte) 0x75, 0, 63});
            // Single transaction write of the 12,288 byte buffer
            oled.writeData(frameBuffer);
            final var elapsedTime = System.currentTimeMillis() - startTime;
            if (elapsedTime < 16) {
                TimeUnit.MILLISECONDS.sleep(16 - elapsedTime);
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
            runDemo(getOled());
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
