/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * High-performance, flicker-free 3D Boing Ball recreation supporting multiple displays.
 * <p>
 * Implements an internal buffered rendering pipeline adaptable to any display dimensions, automatically scaling the ball, grid, and
 * sending frames via FFM memory segments while maintaining compatibility with internal snapshots.
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
     * Software Framebuffer (RGB565 byte array for display FFM transmission).
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
     * Initializes the background grid directly into the internal BufferedImage and frame buffer.
     *
     * @param g2d Graphics2D context of the internal image.
     */
    private final void renderGrid(final Graphics2D g2d) {
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, displayWidth, displayHeight);

        g2d.setColor(new Color(0x10, 0x82, 0x10)); // Dark Gray/Green grid tone
        for (var y = 0; y < displayHeight; y += 16) {
            g2d.drawLine(0, y, displayWidth, y);
        }
        for (var x = 0; x < displayWidth; x += 16) {
            g2d.drawLine(x, 0, x, displayHeight);
        }
    }

    /**
     * Renders a circular black shadow into the internal image context.
     *
     * @param g2d Graphics2D context.
     * @param x Center X of the ball.
     * @param y Center Y of the ball.
     */
    private final void renderShadow(final Graphics2D g2d, final int x, final int y) {
        final var shadowOffset = 4;
        final var sx = x + shadowOffset;
        final var sy = y + shadowOffset;
        final var r2 = radius * radius;

        g2d.setColor(Color.BLACK);
        for (var iy = -radius; iy <= radius; iy++) {
            final var hWidth = (int) Math.sqrt(r2 - (iy * iy));
            g2d.drawLine(sx - hWidth, sy + iy, sx + hWidth, sy + iy);
        }
    }

    /**
     * Renders a 3D convex checkered ball using spherical coordinate mapping onto the internal buffer.
     *
     * @param g2d Graphics2D context.
     * @param x Center X coordinate.
     * @param y Center Y coordinate.
     * @param rot Rotation phase in radians.
     */
    private final void renderBall(final Graphics2D g2d, final int x, final int y, final double rot) {
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
                    g2d.setColor(new Color(255, 0, 0)); // Red
                } else {
                    g2d.setColor(Color.WHITE); // White
                }
                g2d.fillRect(ix, y + iy, 1, 1);
            }
        }
    }

    /**
     * Converts the internal ARGB BufferedImage into an RGB565 byte array buffer.
     *
     * @param bi Source BufferedImage.
     * @param dest Destination byte array buffer.
     * @param width Display width.
     * @param height Display height.
     */
    private final void convertArgbToRgb565(final BufferedImage bi, final byte[] dest, final int width, final int height) {
        var index = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                final var rgb = bi.getRGB(x, y);
                final var r = (rgb >> 16) & 0xFF;
                final var g = (rgb >> 8) & 0xFF;
                final var b = rgb & 0xFF;

                final var rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);

                dest[index++] = (byte) (rgb565 >> 8);
                dest[index++] = (byte) (rgb565 & 0xFF);
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

        // Use the Base class internal buffer directly to avoid secondary copy overhead for snapshots
        final var bi = getImage();
        final var g2d = getG2d();

        while (!Thread.currentThread().isInterrupted() && isRunning()) {
            final var startTime = System.currentTimeMillis();

            synchronized (this) {
                // 1. Scene Assembly directly on the Base shared buffer image
                renderGrid(g2d);
                renderShadow(g2d, (int) ballX, (int) ballY);
                renderBall(g2d, (int) ballX, (int) ballY, phase);

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

                // 3. Convert ARGB image to RGB565 and push to display hardware via FFM
                convertArgbToRgb565(bi, frameBuffer, displayWidth, displayHeight);
            }

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
