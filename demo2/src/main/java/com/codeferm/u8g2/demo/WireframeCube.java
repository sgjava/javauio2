/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2.demo;

import java.lang.foreign.MemorySegment;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.u8g2.U8g2;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * 3D Wireframe Cube with dynamic scaling, randomized movement, and FPS control.
 * <p>
 * Optimized for FFM with zero-allocation in the main loop and precise nano-pacing.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "WireframeCube", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Bouncing 3D cube with dynamic scaling and coordinate clipping")
public class WireframeCube extends Base {

    @Option(names = {"--fps"}, defaultValue = "30", description = "Frames per second (default: ${DEFAULT-VALUE})")
    private int fps;

    /**
     * 3D vertices for a unit cube (coordinates from -1 to 1).
     */
    private final double[][] vertices = {
        {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1},
        {-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1}
    };

    /**
     * The 12 edges connecting the 8 vertices.
     */
    private final int[][] edges = {
        {0, 1}, {1, 2}, {2, 3}, {3, 0},
        {4, 5}, {5, 6}, {6, 7}, {7, 4},
        {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    /**
     * Pre-allocated buffer for projected 2D coordinates to avoid per-frame allocation.
     */
    private final int[][] projected = new int[8][2];

    private final Random random = new Random();

    /**
     * Clips coordinates to display boundaries to prevent driver-level wrapping artifacts.
     */
    private void drawClippedLine(final MemorySegment u8, final int x1, final int y1, final int x2, final int y2, final int w,
            final int h) {
        final var cx1 = (short) Math.max(0, Math.min(x1, w - 1));
        final var cy1 = (short) Math.max(0, Math.min(y1, h - 1));
        final var cx2 = (short) Math.max(0, Math.min(x2, w - 1));
        final var cy2 = (short) Math.max(0, Math.min(y2, h - 1));
        U8g2.u8g2_DrawLine(u8, cx1, cy1, cx2, cy2);
    }

    /**
     * Main animation loop for the rotating and bouncing cube.
     *
     * @param u8 Native MemorySegment for u8g2.
     */
    public void drawCube(final MemorySegment u8) {
        final var screenW = getWidth();
        final var screenH = getHeight();
        final var frameDurationNs = TimeUnit.SECONDS.toNanos(1) / fps;

        // Animation state
        var angleX = 0.0;
        var angleY = 0.0;
        var angleZ = 0.0;
        var posX = (double) screenW / 2.0;
        var posY = (double) screenH / 2.0;

        // Physics state
        final var targetSpeed = 1.3;
        var velX = 1.0;
        var velY = 0.7;
        final var cameraDistance = 3.6;
        final var projectionScale = screenH * 0.60;

        log.info("Starting WireframeCube demo at {} FPS. No allocations in loop.", fps);

        while (true) {
            final var startTime = System.nanoTime();
            U8g2.u8g2_ClearBuffer(u8);

            var minX = 1000;
            var maxX = -1000;
            var minY = 1000;
            var maxY = -1000;

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
                final var offX = (int) (x3 * projectionScale / pZ);
                final var offY = (int) (y3 * projectionScale / pZ);

                // Update relative bounding box
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

                // Store in pre-allocated buffer
                projected[i][0] = (int) (posX + offX);
                projected[i][1] = (int) (posY + offY);
            }

            for (final var edge : edges) {
                drawClippedLine(u8, projected[edge[0]][0], projected[edge[0]][1],
                        projected[edge[1]][0], projected[edge[1]][1],
                        screenW, screenH);
            }

            U8g2.u8g2_SendBuffer(u8);

            // Update physics
            angleX += 0.035;
            angleY += 0.05;
            angleZ += 0.025;
            posX += velX;
            posY += velY;

            // Bounce and Randomize
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

            // Sync FPS using precise nano-sleep
            final var elapsedTime = System.nanoTime() - startTime;
            final var sleepTimeNs = frameDurationNs - elapsedTime;
            if (sleepTimeNs > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(sleepTimeNs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Override
    protected void run(final MemorySegment u8g2) {
        drawCube(u8g2);
    }

    /**
     * Main parsing, error handling and handling user requests for usage help or version help are done with one line of code.
     *
     * @param args Argument list.
     */
    public static void main(String... args) {
        System.exit(new CommandLine(new WireframeCube()).execute(args));
    }
}
