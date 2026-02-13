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
 * This demo simulates a 3D environment on a monochrome display using FFM.
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
     * FPS.
     */
    @Option(names = {"-f", "--fps"}, description = "Frames per second", defaultValue = "30")
    private int fps;

    /**
     * World Map: 1 represents a wall, 0 represents empty space.
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

    // Current player coordinates
    private double posX = 1.5, posY = 1.5;
    // Current direction vector (Where the player is facing)
    private double dirX = 1.0, dirY = 0.0;
    // Camera plane (Field of View - must be perpendicular to direction)
    private double planeX = 0.0, planeY = 0.66;
    private final Random random = new Random();

    /**
     * Renders the 3D scene by casting a ray for every horizontal pixel.
     *
     * @param u8g2 MemorySegment handle to the u8g2 struct.
     */
    public void render(final MemorySegment u8g2) {
        final var w = getWidth();
        final var h = getHeight();
        U8g2.u8g2_ClearBuffer(u8g2);

        // Scan every vertical stripe of the screen
        for (var x = 0; x < w; x++) {
            // Transform screen coordinate x to camera-space coordinate (-1 to 1)
            final var cameraX = 2 * x / (double) w - 1;
            // Calculate direction of the ray
            final var rayDirX = dirX + planeX * cameraX;
            final var rayDirY = dirY + planeY * cameraX;
            // Which grid square the ray is currently in
            var mapX = (int) posX;
            var mapY = (int) posY;
            // Distance the ray has to travel to cross one grid cell line
            final var deltaDistX = Math.abs(1 / rayDirX);
            final var deltaDistY = Math.abs(1 / rayDirY);
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
            while (MAP[mapX][mapY] == 0) {
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
                perpWallDist = (mapX - posX + (1 - stepX) / 2.0) / rayDirX;
            } else {
                perpWallDist = (mapY - posY + (1 - stepY) / 2.0) / rayDirY;
            }

            // Calculate height of the wall slice based on distance
            final var lineHeight = (int) (h / perpWallDist);
            // Calculate the screen pixels where the wall slice starts and ends
            final var drawStart = Math.max(0, -lineHeight / 2 + h / 2);
            final var drawEnd = Math.min(h - 1, lineHeight / 2 + h / 2);

            // Draw the vertical line representing the wall
            U8g2.u8g2_DrawLine(u8g2, (short) x, (short) drawStart, (short) x, (short) drawEnd);
        }
        U8g2.u8g2_SendBuffer(u8g2);
    }

    /**
     * Updates player position and handles collisions.
     */
    private void update(final int action) {
        final var rotSpeed = 0.08;
        final var moveSpeed = 0.05;
        switch (action) {
            case 0 -> { // Forward
                if (MAP[(int) (posX + dirX * moveSpeed)][(int) posY] == 0) {
                    posX += dirX * moveSpeed;
                }
                if (MAP[(int) posX][(int) (posY + dirY * moveSpeed)] == 0) {
                    posY += dirY * moveSpeed;
                }
            }
            case 1 -> { // Backward
                if (MAP[(int) (posX - dirX * moveSpeed)][(int) posY] == 0) {
                    posX -= dirX * moveSpeed;
                }
                if (MAP[(int) posX][(int) (posY - dirY * moveSpeed)] == 0) {
                    posY -= dirY * moveSpeed;
                }
            }
            case 2 ->
                rotate(rotSpeed);  // Turn Left
            case 3 ->
                rotate(-rotSpeed); // Turn Right
        }
    }

    /**
     * Rotates the camera using a 2D rotation matrix.
     */
    private void rotate(final double angle) {
        final var cosA = Math.cos(angle);
        final var sinA = Math.sin(angle);
        final var oldDirX = dirX;
        dirX = dirX * cosA - dirY * sinA;
        dirY = oldDirX * sinA + dirY * cosA;
        final var oldPlaneX = planeX;
        planeX = planeX * cosA - planeY * sinA;
        planeY = oldPlaneX * sinA + planeY * cosA;
    }

    /**
     * Implementation of the Base run method.
     *
     * @param u8g2 MemorySegment handle to the u8g2 structure.
     */
    @Override
    protected void run(final MemorySegment u8g2) {
        final var frameDelay = 1000L / Math.max(1, fps);
        var currentAction = 0;
        log.info("Starting Raycast demo (2000 frames)...");

        for (var i = 0; i < 2000; i++) {
            // Pick a new random movement every 20-40 frames
            if (i % (20 + random.nextInt(20)) == 0) {
                currentAction = random.nextInt(4);
            }
            render(u8g2);
            update(currentAction);
            try {
                Thread.sleep(frameDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("Demo complete.");
    }

    /**
     * Main parsing with automatic type conversion.
     *
     * @param args Argument list.
     */
    public static void main(String... args) {
        System.exit(new CommandLine(new Raycast()).execute(args));
    }
}
