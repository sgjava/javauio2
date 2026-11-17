/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * High-performance, flicker-free 3D Boing Ball recreation supporting multiple displays.
 * <p>
 * Implements a responsive internal buffered rendering pipeline that dynamically scales ball dimensions and physics parameters while
 * maintaining rapid movement and partial dirty-rectangle updates across small and large displays.
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
        description = "Responsive Scaled Buffered 3D Boing Ball with Partial Updates."
)
public class Boing extends Base {

    /**
     * Bytes per pixel (RGB565 format).
     */
    private static final int BYTES_PER_PIXEL = 2;

    /**
     * Effective display width in pixels.
     */
    private int displayWidth;

    /**
     * Effective display height in pixels.
     */
    private int displayHeight;

    /**
     * Radius of the 3D bouncing ball.
     */
    private int radius;

    /**
     * Current horizontal position of the ball center.
     */
    private double ballX;

    /**
     * Current vertical position of the ball center.
     */
    private double ballY;

    /**
     * Previous horizontal position of the ball center (used for dirty clearing).
     */
    private double oldBallX;

    /**
     * Previous vertical position of the ball center (used for dirty clearing).
     */
    private double oldBallY;

    /**
     * Horizontal velocity component.
     */
    private double velX = 2.5;

    /**
     * Vertical velocity component.
     */
    private double velY = 0.0;

    /**
     * Gravitational acceleration factor.
     */
    private double gravity = 0.15;

    /**
     * Bounce energy retention factor.
     */
    private final double bounce = -0.85;

    /**
     * Rotation phase angle in radians for texture mapping.
     */
    private double phase = 0.0;

    /**
     * Off-screen source buffered image instance.
     */
    private BufferedImage bi;

    /**
     * Graphics2D rendering context for the off-screen buffer.
     */
    private Graphics2D g2d;

    /**
     * Renders the static background grid pattern onto the graphics context.
     *
     * @param g Graphics2D context.
     */
    private final void renderGrid(final Graphics2D g) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, displayWidth, displayHeight);

        g.setColor(new Color(0x10, 0x82, 0x10)); // Dark Gray/Green grid tone
        for (var y = 0; y < displayHeight; y += 16) {
            g.drawLine(0, y, displayWidth, y);
        }
        for (var x = 0; x < displayWidth; x += 16) {
            g.drawLine(x, 0, x, displayHeight);
        }
    }

    /**
     * Renders a circular black drop shadow onto the graphics context.
     *
     * @param g Graphics2D context.
     * @param x Center X coordinate of the ball.
     * @param y Center Y coordinate of the ball.
     */
    private final void renderShadow(final Graphics2D g, final int x, final int y) {
        final var shadowOffset = Math.max(2, radius / 10);
        final var sx = x + shadowOffset;
        final var sy = y + shadowOffset;
        final var r2 = radius * radius;

        g.setColor(Color.BLACK);
        for (var iy = -radius; iy <= radius; iy++) {
            final var hWidth = (int) Math.sqrt(r2 - (iy * iy));
            g.drawLine(sx - hWidth, sy + iy, sx + hWidth, sy + iy);
        }
    }

    /**
     * Renders a 3D convex checkered ball using spherical coordinate mapping.
     *
     * @param g Graphics2D context.
     * @param x Center X coordinate.
     * @param y Center Y coordinate.
     * @param rot Rotation phase angle in radians.
     */
    private final void renderBall(final Graphics2D g, final int x, final int y, final double rot) {
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
                    g.setColor(new Color(255, 0, 0)); // Red
                } else {
                    g.setColor(Color.WHITE); // White
                }
                g.fillRect(ix, y + iy, 1, 1);
            }
        }
    }

    /**
     * Converts a specific rectangular sub-region (dirty rect) of the ARGB image into RGB565 format and flushes it over SPI.
     *
     * @param display The display driver instance.
     * @param rect Bounding rectangle marking the dirty screen region.
     */
    private final void pushDirtyRect(final AbstractColorDisplay display, final Rectangle rect) {
        final int rx = Math.max(0, rect.x);
        final int ry = Math.max(0, rect.y);
        final int rw = Math.min(displayWidth - rx, rect.width);
        final int rh = Math.min(displayHeight - ry, rect.height);

        if (rw <= 0 || rh <= 0) {
            return;
        }

        display.setWindow(rx, ry, rw, rh);
        final var segment = display.getImageSegment();

        var index = 0L;
        for (var y = ry; y < ry + rh; y++) {
            for (var x = rx; x < rx + rw; x++) {
                final var rgb = bi.getRGB(x, y);
                final var r = (rgb >> 16) & 0xFF;
                final var g = (rgb >> 8) & 0xFF;
                final var b = rgb & 0xFF;
                final var rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);

                segment.set(ValueLayout.JAVA_BYTE, index++, (byte) (rgb565 >> 8));
                segment.set(ValueLayout.JAVA_BYTE, index++, (byte) rgb565);
            }
        }
        display.writeData(segment.asSlice(0, (long) rw * rh * BYTES_PER_PIXEL));
    }

    /**
     * Main execution loop for the Boing demo, orchestrating physics calculations and partial screen updates.
     *
     * @param display The unified color display driver instance.
     * @throws Exception If hardware communication or memory allocation fails.
     */
    public final void runDemo(final AbstractColorDisplay display) throws Exception {
        log.info("Starting Responsive Scaled Boing Demo with High-Speed Motion...");

        displayWidth = display.getWidth();
        displayHeight = display.getHeight();

        // Dynamically scale ball size and ensure brisk velocity scaling across displays
        final var minDim = Math.min(displayWidth, displayHeight);
        radius = Math.max(20, minDim / 4);
        velX = Math.max(2.0, minDim * 0.007);
        gravity = Math.max(0.12, minDim * 0.00045);

        ballX = displayWidth / 2.0;
        ballY = displayHeight / 3.0;
        oldBallX = ballX;
        oldBallY = ballY;

        bi = getImage();
        g2d = getG2d();

        // 1. Render static background grid once
        renderGrid(g2d);
        display.setWindow(0, 0, displayWidth, displayHeight);

        // Push full initial frame once during startup
        final var fullBuffer = display.getImageSegment();
        var idx = 0L;
        for (var y = 0; y < displayHeight; y++) {
            for (var x = 0; x < displayWidth; x++) {
                final var rgb = bi.getRGB(x, y);
                final var r = (rgb >> 16) & 0xFF;
                final var g = (rgb >> 8) & 0xFF;
                final var b = rgb & 0xFF;
                final var rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);
                fullBuffer.set(ValueLayout.JAVA_BYTE, idx++, (byte) (rgb565 >> 8));
                fullBuffer.set(ValueLayout.JAVA_BYTE, idx++, (byte) rgb565);
            }
        }
        display.writeData(fullBuffer.asSlice(0, (long) displayWidth * displayHeight * BYTES_PER_PIXEL));

        while (!Thread.currentThread().isInterrupted() && isRunning()) {
            final var startTime = System.currentTimeMillis();

            synchronized (this) {
                final int oldX = (int) oldBallX;
                final int oldY = (int) oldBallY;

                // 2. Responsive Physics & Motion Update
                ballX += velX;
                velY += gravity;
                ballY += velY;

                // Horizontal boundary checks
                final var minXLimit = radius;
                final var maxXLimit = (displayWidth - 1) - radius;
                if (ballX < minXLimit || ballX > maxXLimit) {
                    velX *= -1;
                    ballX = Math.clamp(ballX, minXLimit, maxXLimit);
                }

                // Vertical floor boundary check
                final var floorLimit = (displayHeight - 1) - radius;
                if (ballY > floorLimit) {
                    ballY = floorLimit;
                    velY *= bounce;
                    if (Math.abs(velY) < (gravity * 15.0)) {
                        velY = -Math.max(3.0, minDim * 0.025);
                    }
                }
                phase += (velX * 0.12);

                final int newX = (int) ballX;
                final int newY = (int) ballY;

                // 3. Restore background grid slice over previous ball and shadow location
                final int margin = radius + 12;
                final int cleanX = Math.max(0, oldX - margin);
                final int cleanY = Math.max(0, oldY - margin);
                final int cleanW = Math.min(displayWidth - cleanX, margin * 2);
                final int cleanH = Math.min(displayHeight - cleanY, margin * 2);

                g2d.setClip(cleanX, cleanY, cleanW, cleanH);
                renderGrid(g2d);
                g2d.setClip(null);

                // 4. Render new shadow and ball positions
                renderShadow(g2d, newX, newY);
                renderBall(g2d, newX, newY, phase);

                // 5. Compute bounding box containing old and new areas
                final int dirtyX = Math.min(oldX, newX) - margin;
                final int dirtyY = Math.min(oldY, newY) - margin;
                final int dirtyW = Math.max(oldX, newX) - dirtyX + (margin * 2);
                final int dirtyH = Math.max(oldY, newY) - dirtyY + (margin * 2);

                final var dirtyRect = new Rectangle(dirtyX, dirtyY, dirtyW, dirtyH);

                // 6. Push only the modified dirty rectangle over SPI
                pushDirtyRect(display, dirtyRect);

                oldBallX = ballX;
                oldBallY = ballY;
            }

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
     * Entry point for the Command Line Interface execution.
     *
     * @return Exit code (0 for success).
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
