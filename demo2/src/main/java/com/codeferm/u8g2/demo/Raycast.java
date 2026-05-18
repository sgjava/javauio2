/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2.demo;

import java.lang.foreign.MemorySegment;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.u8g2.U8g2;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * High-performance 3D Raycasting Engine demo simulation for monochrome displays via FFM. Addresses asynchronous interrupt
 * conditions cleanly via standard shutdown hooks and fixes dimensional array coordinate indexing.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Raycast", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Clean 3D Raycasting walkthrough with full var and final")
public class Raycast extends Base {

    /**
     * Target execution frames per second parameter option.
     */
    @Option(names = {"-f", "--fps"}, description = "Frames per second", defaultValue = "30")
    private int fps;

    /**
     * World Map Layout Definition Matrix: 1 represents structural boundaries, 0 is clear space.
     */
    private static final int[][] MAP = {
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1},
        {1, 0, 0, 0, 0, 0, 0, 0, 0, 1},
        {1, 0, 1, 0, 0, 1, 0, 1, 0, 1},
        {1, 0, 1, 0, 0, 1, 0, 1, 0, 1},
        {1, 0, 0, 0, 0, 0, 0, 0, 0, 1},
        {1, 1, 1, 1, 0, 1, 1, 1, 1, 1},
        {1, 0, 0, 0, 0, 0, 0, 0, 0, 1},
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1}
    };

    /**
     * Asynchronous execution state tracking flag mapped to runtime shutdown triggers.
     */
    private volatile boolean running = true;

    /**
     * Current coordinate offset location mapping along the X axis.
     */
    private double posX = 1.5;

    /**
     * Current coordinate offset location mapping along the Y axis.
     */
    private double posY = 1.5;

    /**
     * Current directional trajectory look vector along the X axis component.
     */
    private double dirX = 1.0;

    /**
     * Current directional trajectory look vector along the Y axis component.
     */
    private double dirY = 0.0;

    /**
     * Viewport projection camera plane alignment vector along the X axis component.
     */
    private double planeX = 0.0;

    /**
     * Viewport projection camera plane alignment vector along the Y axis component.
     */
    private double planeY = 0.66;

    /**
     * Random seed value instance mapping for execution trajectory generation passes.
     */
    private final Random random = new Random();

    /**
     * Default constructor initializing base properties.
     */
    public Raycast() {
        super();
    }

    /**
     * Renders a single frame projection pass across horizontal boundaries using a DDA strategy.
     *
     * @param u8g2 MemorySegment structure descriptor handle mapping down to the active device buffer.
     */
    public void render(final MemorySegment u8g2) {
        final var w = getWidth();
        final var h = getHeight();
        U8g2.u8g2_ClearBuffer(u8g2);

        // Scan every vertical stripe of the screen
        for (var x = 0; x < w; x++) {
            // Transform screen coordinate x to camera-space coordinate (-1 to 1)
            final var cameraX = (2.0 * x / w) - 1.0;
            // Calculate direction of the ray
            final var rayDirX = dirX + (planeX * cameraX);
            final var rayDirY = dirY + (planeY * cameraX);
            // Which grid square the ray is currently in
            var mapX = (int) posX;
            var mapY = (int) posY;
            // Distance the ray has to travel to cross one grid cell line
            final var deltaDistX = (rayDirX == 0) ? Double.MAX_VALUE : Math.abs(1.0 / rayDirX);
            final var deltaDistY = (rayDirY == 0) ? Double.MAX_VALUE : Math.abs(1.0 / rayDirY);
            // Distance from current position to the first grid lines
            var sideDistX = 0.0;
            var sideDistY = 0.0;
            // Length of the perpendicular ray (to avoid fisheye effect)
            var perpWallDist = 0.0;
            // What direction to step in the grid
            var stepX = 0;
            var stepY = 0;
            // Track which side of the wall was hit (0 for X-side, 1 for Y-side)
            var side = 0;

            // Initialize step and sideDist based on ray direction
            if (rayDirX < 0) {
                stepX = -1;
                sideDistX = (posX - mapX) * deltaDistX;
            } else {
                stepX = 1;
                sideDistX = (mapX + 1.0 - posX) * deltaDistX;
            }
            if (rayDirY < 0) {
                stepY = -1;
                sideDistY = (posY - mapY) * deltaDistY;
            } else {
                stepY = 1;
                sideDistY = (mapY + 1.0 - posY) * deltaDistY;
            }

            // --- DDA Algorithm Loop ---
            // Coordinate matrices mapped cleanly to array bounds syntax [row][column] (Y represents rows, X columns)
            while ((mapY >= 0) && (mapY < MAP.length) && (mapX >= 0) && (mapX < MAP[0].length) && (MAP[mapY][mapX] == 0)) {
                if (sideDistX < sideDistY) {
                    sideDistX += deltaDistX;
                    mapX += stepX;
                    side = 0;
                } else {
                    sideDistY += deltaDistY;
                    mapY += stepY;
                    side = 1;
                }
            }

            // Calculate distance to the wall projected onto the camera direction
            if (side == 0) {
                perpWallDist = (mapX - posX + ((1.0 - stepX) / 2.0)) / rayDirX;
            } else {
                perpWallDist = (mapY - posY + ((1.0 - stepY) / 2.0)) / rayDirY;
            }

            // Avoid division by zero bugs if player steps right up against a boundary line
            if (perpWallDist <= 0.0) {
                perpWallDist = 0.01;
            }

            // Calculate height of the wall slice based on distance
            final var lineHeight = (int) (h / perpWallDist);
            // Calculate the screen pixels where the wall slice starts and ends
            final var drawStart = Math.max(0, (-lineHeight / 2) + (h / 2));
            final var drawEnd = Math.min(h - 1, (lineHeight / 2) + (h / 2));

            // Draw the vertical line representing the wall
            U8g2.u8g2_DrawLine(u8g2, (short) x, (short) drawStart, (short) x, (short) drawEnd);
        }
        U8g2.u8g2_SendBuffer(u8g2);
    }

    /**
     * Adjusts structural placement offsets and applies collision boundary tracking structures.
     *
     * @param action Targeted structural routing operation identifier.
     */
    private void update(final int action) {
        final var rotSpeed = 0.08;
        final var moveSpeed = 0.05;
        switch (action) {
            case 0 -> { // Forward
                final var nextX = posX + (dirX * moveSpeed);
                final var nextY = posY + (dirY * moveSpeed);
                // Grid bounds check mapping matching row/col definitions
                if ((nextY >= 0) && (nextY < MAP.length) && (int) posX >= 0 && (int) posX < MAP[0].length
                        && MAP[(int) posY][(int) nextX] == 0) {
                    posX = nextX;
                }
                if (((int) posX >= 0) && ((int) posX < MAP[0].length) && nextY >= 0 && nextY < MAP.length
                        && MAP[(int) nextY][(int) posX] == 0) {
                    posY = nextY;
                }
            }
            case 1 -> { // Backward
                final var nextX = posX - (dirX * moveSpeed);
                final var nextY = posY - (dirY * moveSpeed);
                if ((nextY >= 0) && (nextY < MAP.length) && (int) posX >= 0 && (int) posX < MAP[0].length
                        && MAP[(int) posY][(int) nextX] == 0) {
                    posX = nextX;
                }
                if (((int) posX >= 0) && ((int) posX < MAP[0].length) && nextY >= 0 && nextY < MAP.length
                        && MAP[(int) nextY][(int) posX] == 0) {
                    posY = nextY;
                }
            }
            case 2 ->
                rotate(rotSpeed);  // Turn Left
            case 3 ->
                rotate(-rotSpeed); // Turn Right
            default -> {
                // No operational state shift applied
            }
        }
    }

    /**
     * Multiplies coordinates against a standard 2D rotation structural matrix framework.
     *
     * @param angle Numerical angular value increment to skew layout frames by.
     */
    private void rotate(final double angle) {
        final var cosA = Math.cos(angle);
        final var sinA = Math.sin(angle);
        final var oldDirX = dirX;
        dirX = (dirX * cosA) - (dirY * sinA);
        dirY = (oldDirX * sinA) + (dirY * cosA);
        final var oldPlaneX = planeX;
        planeX = (planeX * cosA) - (planeY * sinA);
        planeY = (oldPlaneX * sinA) + (planeY * cosA);
    }

    /**
     * Managed implementation overriding the abstract base loop cycle.
     *
     * @param u8g2 Native tracking handle pointing down to unmanaged runtime data allocations.
     */
    @Override
    protected void run(final MemorySegment u8g2) {
        final var frameDelay = 1000L / Math.max(1, fps);
        var currentAction = 0;
        log.info("Starting Raycast demo (2000 frames)...");

        // Attaching active thread coordination hooks to clean loops up during interrupt loops
        final var mainThread = Thread.currentThread();
        final var shutdownHook = new Thread(() -> {
            log.debug("Interrupt caught! Changing tracking flags to release rendering loops...");
            this.running = false;
            mainThread.interrupt();
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            for (var i = 0; (i < 2000) && running; i++) {
                // Pick a new random movement every 20-40 frames
                if (i % (20 + random.nextInt(20)) == 0) {
                    currentAction = random.nextInt(4);
                }
                render(u8g2);
                update(currentAction);

                Thread.sleep(frameDelay);
            }
        } catch (InterruptedException e) {
            log.debug("Processing cycle interrupted during application finalization sequences.");
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // Catch standard faults if processing teardown is active via secondary routines
            }
            log.info("Demo complete.");
        }
    }

    /**
     * Execution script driver entry sequence mapping options array values directly to command parsers.
     *
     * @param args Array mapping configurations structure details input strings.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new Raycast()).execute(args));
    }
}
