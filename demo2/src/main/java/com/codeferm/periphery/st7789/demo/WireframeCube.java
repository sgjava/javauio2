/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.st7789.demo;

import com.codeferm.periphery.device.St7789;
import java.awt.Color;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * 3D Wireframe Cube for ST7789 with phasing color transitions using FFM.
 * <p>
 * This demo renders a rotating 3D cube that smoothly cycles through colors (Red -> Green -> Blue) using sine-wave oscillators. It
 * utilizes {@code Base} class resources for frame preparation, optimizes performance using dirty-region canvas clearing, and pushes
 * frames to the ST7789 hardware via zero-allocation FFM segments.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "WireframeCube", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Bouncing 3D cube with phasing colors for ST7789")
public class WireframeCube extends Base {

    /**
     * Picocli command spec used to differentiate between default FPS and user-provided FPS.
     */
    @Spec
    private CommandSpec spec;

    /**
     * Random number generator for movement randomization.
     */
    private final Random random = new Random();

    /**
     * 3D vertices for a unit cube (coordinates from -1 to 1).
     */
    private final double[][] vertices = {
        {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1},
        {-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1}
    };

    /**
     * The 12 edges connecting the 8 vertices of the cube.
     */
    private final int[][] edges = {
        {0, 1}, {1, 2}, {2, 3}, {3, 0},
        {4, 5}, {5, 6}, {6, 7}, {7, 4},
        {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    /**
     * Calculates a phasing RGB color based on an oscillator input.
     * <p>
     * Uses three sine waves shifted by 120 degrees (2π/3) to create a smooth transition through the color spectrum.
     * </p>
     *
     * @param time The current animation time/angle used as the oscillator input.
     * @return A {@link Color} object representing the phased color.
     */
    private Color getPhasingColor(final double time) {
        // Frequency of the color cycle
        final var frequency = 0.5;
        // Shift phases for R, G, and B to create the cycling effect
        final var r = (int) (Math.sin(frequency * time + 0) * 127 + 128);
        final var g = (int) (Math.sin(frequency * time + 2 * Math.PI / 3) * 127 + 128);
        final var b = (int) (Math.sin(frequency * time + 4 * Math.PI / 3) * 127 + 128);
        return new Color(Math.clamp(r, 0, 255), Math.clamp(g, 0, 255), Math.clamp(b, 0, 255));
    }

    /**
     * Main animation loop for the rotating, bouncing, and color-phasing cube using dirty-region optimization.
     *
     * @param lcd ST7789 driver instance.
     */
    public final void drawCube(final St7789 lcd) {
        final var screenW = getWidth();
        final var screenH = getHeight();
        final var g2d = getG2d();

        // Animation state
        var angleX = 0.0;
        var angleY = 0.0;
        var angleZ = 0.0;
        var posX = (double) screenW / 2.0;
        var posY = (double) screenH / 2.0;

        // Physics/Movement state
        final var targetSpeed = 1.3;
        var velX = 1.0;
        var velY = 0.7;
        final var cameraDistance = 3.6;
        final var projectionScale = screenH * 0.60;
        final var frameDelayNs = TimeUnit.SECONDS.toNanos(1) / getFps();

        log.info("Starting Phasing Wireframe Cube with Dirty Updates at {} FPS", getFps());

        // Initial full-screen clear
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, screenW, screenH);
        lcd.drawImage(getImage());

        // Track previous bounding box for dirty screen clearing
        var prevMinDrawX = 0;
        var prevMinDrawY = 0;
        var prevMaxDrawW = screenW;
        var prevMaxDrawH = screenH;

        while (isRunning()) {
            final var startTime = System.nanoTime();

            // --- Dirty Update Optimization: Clear only the previous frame's bounding box area ---
            g2d.setColor(Color.BLACK);
            g2d.fillRect(prevMinDrawX, prevMinDrawY, prevMaxDrawW, prevMaxDrawH);

            // Set the phasing color for the cube edges
            g2d.setColor(getPhasingColor(angleX));

            final var projected = new int[8][2];
            var minX = 1000.0;
            var maxX = -1000.0;
            var minY = 1000.0;
            var maxY = -1000.0;

            for (var i = 0; i < 8; i++) {
                final var x = vertices[i][0];
                final var y = vertices[i][1];
                final var z = vertices[i][2];

                // --- 3D Rotation ---
                final var cX = Math.cos(angleX);
                final var sX = Math.sin(angleX);
                final var y1 = y * cX - z * sX;
                final var z1 = y * sX + z * cX;
                final var cY = Math.cos(angleY);
                final var sY = Math.sin(angleY);
                final var x2 = x * cY + z1 * sY;
                final var z2 = -x * sY + z1 * cY;
                final var cZ = Math.cos(angleZ);
                final var sZ = Math.sin(angleZ);
                final var x3 = x2 * cZ - y1 * sZ;
                final var y3 = x2 * sZ + y1 * cZ;

                // --- Perspective Projection ---
                final var pZ = z2 + cameraDistance;
                final var offX = (x3 * projectionScale / pZ);
                final var offY = (y3 * projectionScale / pZ);

                if (offX < minX) {
                    minX = offX;
                }
                if (offX > maxX) {
                    maxX = offX;
                }
                if (offY < minY) {
                    minY = offY;
                }
                if (offY > maxY) {
                    maxY = offY;
                }

                projected[i][0] = (int) (posX + offX);
                projected[i][1] = (int) (posY + offY);
            }

            // Draw edges
            for (final var edge : edges) {
                g2d.drawLine(projected[edge[0]][0], projected[edge[0]][1],
                        projected[edge[1]][0], projected[edge[1]][1]);
            }

            // Calculate current dirty bounding box with padding for anti-aliasing/strokes
            final var padding = 4;
            final var currentMinX = (int) (posX + minX) - padding;
            final var currentMinY = (int) (posY + minY) - padding;
            final var currentMaxX = (int) (posX + maxX) + padding;
            final var currentMaxY = (int) (posY + maxY) + padding;

            // Clamp coordinates to screen dimensions
            final var drawX = Math.max(0, Math.min(screenW, currentMinX));
            final var drawY = Math.max(0, Math.min(screenH, currentMinY));
            final var drawW = Math.min(screenW - drawX, Math.max(1, currentMaxX - currentMinX));
            final var drawH = Math.min(screenH - drawY, Math.max(1, currentMaxY - currentMinY));

            // Push frame to hardware via FFM driver
            lcd.drawImage(getImage());

            // Cache current bounding box metrics for the next frame's clear cycle
            prevMinDrawX = drawX;
            prevMinDrawY = drawY;
            prevMaxDrawW = drawW;
            prevMaxDrawH = drawH;

            // Update rotation and position
            angleX += 0.035;
            angleY += 0.05;
            angleZ += 0.025;
            posX += velX;
            posY += velY;

            // Handle bouncing and randomization
            var bounced = false;
            if (posX + minX <= 0 || posX + maxX >= screenW) {
                velX = -velX;
                posX = (posX + minX <= 0) ? -minX + 1 : screenW - maxX - 1;
                bounced = true;
            }
            if (posY + minY <= 0 || posY + maxY >= screenH) {
                velY = -velY;
                posY = (posY + minY <= 0) ? -minY + 1 : screenH - maxY - 1;
                bounced = true;
            }
            if (bounced) {
                final var newAngle = Math.atan2(velY, velX) + (random.nextDouble() - 0.5) * 0.3;
                velX = Math.cos(newAngle) * targetSpeed;
                velY = Math.sin(newAngle) * targetSpeed;
            }

            // Enforce target FPS using nanoTime for precision
            final var endTime = System.nanoTime();
            final var sleepTime = frameDelayNs - (endTime - startTime);
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime / 1_000_000, (int) (sleepTime % 1_000_000));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Application entry point for the demo.
     * <p>
     * Initializes hardware and manages the animation lifecycle. If the user does not provide an FPS via CLI, it defaults to 30.
     * </p>
     *
     * @return Exit code.
     * @throws Exception If hardware initialization fails.
     */
    @Override
    public Integer call() throws Exception {
        final var fpsMatched = spec.commandLine().getParseResult().hasMatchedOption("fps");

        if (!fpsMatched) {
            setFps(30);
        }

        super.call();
        drawCube(getLcd());
        done();
        return 0;
    }

    /**
     * Main method.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new WireframeCube()).execute(args));
    }
}
