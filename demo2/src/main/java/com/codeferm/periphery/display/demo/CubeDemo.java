/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import java.awt.Color;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * High-performance 3D Wireframe Cube Demo supporting multiple displays with dynamic sizing, toggleable line/buffer rendering, and
 * selective line erasure.
 * <p>
 * This demo calculates 3D vertex rotations for multiple cubes, projecting them onto a 2D plane. Line mode utilizes selective line
 * erasure for flicker-free GAC hardware rendering, while buffer mode uses an accelerated software framebuffer.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(
        name = "CubeDemo",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "3D wireframe cubes on color displays."
)
public class CubeDemo extends Base {

    /**
     * Use direct line primitives (true) or software buffer (false). Default is true for hardware acceleration.
     */
    @Option(
            names = {"-l", "--line"},
            arity = "0..1",
            description = "Use hardware/direct line primitives (default: ${DEFAULT-Value})"
    )
    private boolean line = true;

    /**
     * Random number generator for initial state and colors.
     */
    private final Random random = new Random();

    private int displayWidth;
    private int displayHeight;

    /**
     * Internal state container for a 3D Cube. Manages its own physics, rotation, vertex data, and previous frame coordinates.
     */
    private static final class Cube {

        double x;
        double y;
        double vx;
        double vy;
        double angleX;
        double angleY;
        double angleZ;
        final double rotX;
        final double rotY;
        final double rotZ;
        final int size;
        final int colorRgb;
        final Color colorAwt;

        final int[] prevPx = new int[8];
        final int[] prevPy = new int[8];
        boolean hasDrawn = false;

        private final double[][] vertices = {
            {-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1},
            {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1}
        };

        private final int[][] edges = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0},
            {4, 5}, {5, 6}, {6, 7}, {7, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };

        Cube(final int minDim, final int rgb, final Color awt, final int screenW, final int screenH, final Random rnd) {
            this.size = Math.max(8, minDim / 10 + rnd.nextInt(Math.max(1, minDim / 15)));
            this.colorRgb = rgb;
            this.colorAwt = awt;

            final var margin = (int) (this.size * 1.6);
            this.x = margin + rnd.nextInt(Math.max(1, screenW - (margin * 2)));
            this.y = margin + rnd.nextInt(Math.max(1, screenH - (margin * 2)));

            this.vx = (rnd.nextDouble() * 1.6) - 0.8;
            this.vy = (rnd.nextDouble() * 1.6) - 0.8;
            this.rotX = 0.02 + (rnd.nextDouble() * 0.06);
            this.rotY = 0.02 + (rnd.nextDouble() * 0.06);
            this.rotZ = rnd.nextDouble() * 0.03;
        }

        void update(final int screenW, final int screenH) {
            x += vx;
            y += vy;
            final var padding = (int) (size * 1.6);
            if (x < padding || x > screenW - padding) {
                vx *= -1;
            }
            if (y < padding || y > screenH - padding) {
                vy *= -1;
            }
            angleX += rotX;
            angleY += rotY;
            angleZ += rotZ;
        }
    }

    /**
     * Calculates projected coordinates for a cube's vertices.
     *
     * @param cube The cube instance.
     * @param px Output array for projected X coordinates.
     * @param py Output array for projected Y coordinates.
     */
    private void calculateVertices(final Cube cube, final int[] px, final int[] py) {
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

            // Project 3D to 2D and clamp to display limits
            px[i] = Math.clamp((int) (cube.x + (tx2 * cube.size)), 0, displayWidth - 1);
            py[i] = Math.clamp((int) (cube.y + (ty2 * cube.size)), 0, displayHeight - 1);
        }
    }

    /**
     * Renders cubes using direct line primitives with selective line erasure (enables hardware GAC).
     */
    private void renderDirect(final AbstractColorDisplay display, final Cube[] cubes) {
        final var px = new int[8];
        final var py = new int[8];

        for (final var cube : cubes) {
            // Erase previous frame's lines for this cube if it has been drawn before
            if (cube.hasDrawn) {
                for (final var edge : cube.edges) {
                    display.drawLine(cube.prevPx[edge[0]], cube.prevPy[edge[0]],
                            cube.prevPx[edge[1]], cube.prevPy[edge[1]], 0);
                }
            }

            // Calculate new vertex coordinates
            calculateVertices(cube, px, py);

            // Draw new lines for this cube
            for (final var edge : cube.edges) {
                display.drawLine(px[edge[0]], py[edge[0]], px[edge[1]], py[edge[1]], cube.colorRgb);
            }

            // Save current coordinates as previous for next frame erasure
            System.arraycopy(px, 0, cube.prevPx, 0, 8);
            System.arraycopy(py, 0, cube.prevPy, 0, 8);
            cube.hasDrawn = true;
        }
    }

    /**
     * Renders cubes using the software Graphics2D backbuffer (optimized for ST7789).
     */
    private void renderBuffered(final AbstractColorDisplay display, final Cube[] cubes, final java.awt.Graphics2D g2d) {
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, displayWidth, displayHeight);

        final var px = new int[8];
        final var py = new int[8];

        for (final var cube : cubes) {
            g2d.setColor(cube.colorAwt);
            calculateVertices(cube, px, py);
            for (final var edge : cube.edges) {
                g2d.drawLine(px[edge[0]], py[edge[0]], px[edge[1]], py[edge[1]]);
            }
        }
        display.drawImage(getImage());
    }

    /**
     * Main execution loop for the CubeDemo.
     *
     * @param display The unified display driver instance.
     * @throws Exception If hardware communication fails.
     */
    public final void runDemo(final AbstractColorDisplay display) throws Exception {
        log.info("Starting Multi-Cube 3D Demo [Line mode: {}]...", line);

        displayWidth = display.getWidth();
        displayHeight = display.getHeight();
        final var minDim = Math.min(displayWidth, displayHeight);
        final var g2d = line ? null : getG2d();

        display.clear();

        final var cubes = new Cube[]{
            new Cube(minDim, 0xFF0000, Color.RED, displayWidth, displayHeight, random),
            new Cube(minDim, 0x00FF00, Color.GREEN, displayWidth, displayHeight, random),
            new Cube(minDim, 0x0000FF, Color.BLUE, displayWidth, displayHeight, random),
            new Cube(minDim, 0xFFFF00, Color.YELLOW, displayWidth, displayHeight, random),
            new Cube(minDim, 0xFF00FF, Color.MAGENTA, displayWidth, displayHeight, random),
            new Cube(minDim, 0x00FFFF, Color.CYAN, displayWidth, displayHeight, random)
        };

        while (isRunning() && !Thread.currentThread().isInterrupted()) {
            final var startTime = System.currentTimeMillis();

            // Update physics/rotations for all cubes
            for (final var cube : cubes) {
                cube.update(displayWidth, displayHeight);
            }

            // Render based on selected pipeline option
            if (line) {
                renderDirect(display, cubes);
            } else {
                renderBuffered(display, cubes, g2d);
            }

            final var elapsedTime = System.currentTimeMillis() - startTime;
            final var targetDelay = 1000 / getFps();

            if (elapsedTime < targetDelay) {
                try {
                    TimeUnit.MILLISECONDS.sleep(targetDelay - elapsedTime);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
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
            runDemo(getDisplay());
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
        System.exit(new CommandLine(new CubeDemo()).execute(args));
    }
}
