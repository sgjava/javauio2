/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.ssd1331.demo;

import com.codeferm.periphery.device.Ssd1331;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * High-performance 3D Wireframe Demo leveraging SSD1331 GAC Hardware.
 * <p>
 * This demo calculates 3D vertex rotations for multiple cubes and projects them onto a 2D plane. Rendering is performed using the
 * SSD1331's Graphic Acceleration Command (GAC) for hardware-accelerated line drawing, ensuring high frame rates with minimal CPU
 * overhead.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(
        name = "GacCubeDemo",
        mixinStandardHelpOptions = true,
        version = "1.0.0",
        description = "Hardware-accelerated 3D wireframe cubes."
)
public class GacCubeDemo extends Base {

    /**
     * Random number generator for initial state and colors.
     */
    private final Random random = new Random();

    /**
     * Internal state container for a 3D Cube. Manages its own physics, rotation, and vertex data.
     */
    private static final class Cube {

        /**
         * Horizontal screen position.
         */
        double x;
        /**
         * Vertical screen position.
         */
        double y;
        /**
         * Horizontal velocity.
         */
        double vx;
        /**
         * Vertical velocity.
         */
        double vy;
        /**
         * Current rotation angle on X axis.
         */
        double angleX;
        /**
         * Current rotation angle on Y axis.
         */
        double angleY;
        /**
         * Current rotation angle on Z axis.
         */
        double angleZ;
        /**
         * Fixed rotation speed for X axis.
         */
        final double rotX;
        /**
         * Fixed rotation speed for Y axis.
         */
        final double rotY;
        /**
         * Fixed rotation speed for Z axis.
         */
        final double rotZ;
        /**
         * Half-length of the cube side.
         */
        final int size;
        /**
         * Red color component (0-63).
         */
        final int r;
        /**
         * Green color component (0-63).
         */
        final int g;
        /**
         * Blue color component (0-63).
         */
        final int b;

        /**
         * 8 vertices of a unit cube centered at (0,0,0).
         */
        private final double[][] vertices = {
            {-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1},
            {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1}
        };

        /**
         * 12 edges connecting the vertex indices.
         */
        private final int[][] edges = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0},
            {4, 5}, {5, 6}, {6, 7}, {7, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };

        /**
         * Constructs a new Cube with randomized physics.
         *
         * * @param size The scale of the cube.
         * @param r Red component.
         * @param g Green component.
         * @param b Blue component.
         * @param rnd Random instance for initialization.
         */
        Cube(final int size, final int r, final int g, final int b, final Random rnd) {
            this.size = size;
            this.r = r;
            this.g = g;
            this.b = b;
            this.x = 20 + rnd.nextInt(50);
            this.y = 20 + rnd.nextInt(20);
            this.vx = (rnd.nextDouble() * 1.5) - 0.75;
            this.vy = (rnd.nextDouble() * 1.5) - 0.75;
            this.rotX = 0.02 + (rnd.nextDouble() * 0.12);
            this.rotY = 0.02 + (rnd.nextDouble() * 0.12);
            this.rotZ = rnd.nextDouble() * 0.05;
        }

        /**
         * Updates cube position and rotation angles. Includes boundary checking for screen edges.
         */
        void update() {
            x += vx;
            y += vy;
            // Pad boundary to account for rotation extension (sqrt(3) factor)
            final var padding = (int) (size * 1.6);
            if (x < padding || x > 95 - padding) {
                vx *= -1;
            }
            if (y < padding || y > 63 - padding) {
                vy *= -1;
            }
            angleX += rotX;
            angleY += rotY;
            angleZ += rotZ;
        }
    }

    /**
     * Renders a 3D wireframe cube using GAC hardware line commands.
     *
     * @param oled SSD1331 driver instance.
     * @param cube The cube object to render.
     * @param erase If true, draws the cube in black (0,0,0).
     */
    private void renderCube(final Ssd1331 oled, final Cube cube, final boolean erase) {
        final var r = erase ? 0 : cube.r;
        final var g = erase ? 0 : cube.g;
        final var b = erase ? 0 : cube.b;

        final var px = new int[8];
        final var py = new int[8];

        for (var i = 0; i < 8; i++) {
            var vx = cube.vertices[i][0];
            var vy = cube.vertices[i][1];
            var vz = cube.vertices[i][2];

            // 3D Rotation Math (X, Y, then Z axis)
            final var cosX = Math.cos(cube.angleX);
            final var sinX = Math.sin(cube.angleX);
            final var ty = vy * cosX - vz * sinX;
            final var tz = vy * sinX + vz * cosX;
            vy = ty;
            vz = tz;

            final var cosY = Math.cos(cube.angleY);
            final var sinY = Math.sin(cube.angleY);
            final var tx = vx * cosY + vz * sinY;
            vx = tx;

            final var cosZ = Math.cos(cube.angleZ);
            final var sinZ = Math.sin(cube.angleZ);
            final var tx2 = vx * cosZ - vy * sinZ;
            final var ty2 = vx * sinZ + vy * cosZ;

            // Project 3D to 2D and clamp to hardware display limits
            px[i] = Math.clamp((int) (cube.x + (tx2 * cube.size)), 0, 95);
            py[i] = Math.clamp((int) (cube.y + (ty2 * cube.size)), 0, 63);
        }

        // Output to hardware using GAC Draw Line command
        for (final var edge : cube.edges) {
            oled.drawLine(px[edge[0]], py[edge[0]], px[edge[1]], py[edge[1]], r, g, b);
        }
    }

    /**
     * Renders multiple cubes using GAC hardware acceleration. Uses a signal-aware loop to allow for clean shutdown via Base
     * timer/hook.
     *
     * @param oled SSD1331 driver instance.
     * @throws Exception on hardware failure.
     */
    public final void runDemo(final Ssd1331 oled) throws Exception {
        log.info("Starting Multi-Cube GAC 3D Demo (High Standards)...");
        oled.setup();
        // Set remap for RGB mode
        oled.writeCommand(new byte[]{(byte) 0xA4, Ssd1331.SET_REMAP, (byte) 0x72});
        oled.clear();

        final var cubes = new Cube[]{
            new Cube(7, 63, 0, 0, random), // Red
            new Cube(10, 0, 63, 0, random), // Green
            new Cube(6, 0, 0, 63, random), // Blue
            new Cube(9, 63, 63, 0, random), // Yellow
            new Cube(5, 63, 0, 63, random), // Magenta
            new Cube(11, 0, 63, 63, random) // Cyan
        };

        // Check both the volatile running flag and the thread interrupt status
        while (isRunning() && !Thread.currentThread().isInterrupted()) {
            final var startTime = System.currentTimeMillis();

            for (final var cube : cubes) {
                // Erase -> Update -> Draw cycle for flicker-free movement
                renderCube(oled, cube, true);
                cube.update();
                renderCube(oled, cube, false);
            }

            // Maintain stable frame rate (~60 FPS)
            final var elapsedTime = System.currentTimeMillis() - startTime;
            final var targetDelay = 1000 / getFps();

            if (elapsedTime < targetDelay) {
                try {
                    TimeUnit.MILLISECONDS.sleep(targetDelay - elapsedTime);
                } catch (final InterruptedException e) {
                    // Re-assert interrupt so the while loop terminates naturally
                    Thread.currentThread().interrupt();
                    // Log the event concisely and exit the loop
                    log.debug("Demo loop interrupted during sleep, exiting gracefully");
                    break;
                }
            }
        }
    }

    /**
     * Picocli entry point.
     *
     * @return 0 on success.
     * @throws Exception if demo fails.
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
     * Main application method.
     *
     * @param args Command line arguments.
     */
    public static void main(final String[] args) {
        System.exit(new CommandLine(new GacCubeDemo()).execute(args));
    }
}
