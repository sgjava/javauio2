/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.ssd1331.demo;

import com.codeferm.periphery.device.Ssd1331;
import java.awt.Color;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * 3D Raycasting demo for SSD1331 featuring irregular "Castle Ashlar" stone facades using FFM.
 * <p>
 * This engine renders an old-school dungeon environment. It uses coordinate-based hashing to generate irregular stone sizes and
 * varying earthy tones. The navigation system uses a weighted random-walk with collision-avoidance to prevent the player from
 * getting stuck against wall textures.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Raytrace", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Castle raycaster with improved navigation and irregular masonry")
public class Raytrace extends Base {

    /**
     * Picocli command spec for inspecting parse results.
     */
    @Spec
    private CommandSpec spec;

    /**
     * Random generator for autonomous movement logic.
     */
    private final Random random = new Random();

    /**
     * World map grid: 1 represents a stone wall, 0 represents walkable space. Dimensions are 9x10 (rows x columns).
     */
    private final int[][] worldMap = {
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1},
        {1, 0, 0, 0, 0, 0, 0, 0, 0, 1},
        {1, 0, 1, 0, 0, 0, 1, 1, 0, 1},
        {1, 0, 1, 0, 1, 0, 0, 1, 0, 1},
        {1, 0, 0, 0, 1, 1, 0, 0, 0, 1},
        {1, 0, 1, 0, 0, 0, 0, 1, 0, 1},
        {1, 0, 1, 1, 1, 1, 0, 1, 0, 1},
        {1, 0, 0, 0, 0, 0, 0, 0, 0, 1},
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1}
    };

    /**
     * Main rendering and exploration loop.
     * <p>
     * Features "Stretcher-Jitter" logic to create irregular stone blocks and a weighted random-walk to ensure continuous movement.
     * Renders to the optimized FFM buffer. Navigation is constrained to prevent extreme close-ups of wall textures.
     * </p>
     *
     * @param oled SSD1331 driver instance used for foreign memory transfers.
     */
    public final void runDemo(final Ssd1331 oled) {
        final var width = getWidth();
        final var height = getHeight();
        final var g2d = getG2d();

        // Player state
        var posX = 4.5;
        var posY = 1.5;
        var dirX = -1.0;
        var dirY = 0.0;
        var planeX = 0.0;
        var planeY = 0.66;

        // Navigation state
        var moveState = 0; // 0: Forward, 1: Back, 2: Left, 3: Right
        var stateTicks = 0;

        final var frameDelay = 1000 / getFps();
        log.info("Starting Castle Exploration via FFM at {} FPS", getFps());

        while (true) {
            final var startTime = System.currentTimeMillis();

            // Clear ceiling (Sky/Atmosphere) and floor
            g2d.setColor(new Color(15, 12, 10));
            g2d.fillRect(0, 0, width, height / 2);
            g2d.setColor(new Color(25, 25, 25));
            g2d.fillRect(0, height / 2, width, height / 2);

            // Raycasting loop
            for (var x = 0; x < width; x++) {
                final var cameraX = 2.0 * x / (double) width - 1.0;
                final var rayDirX = dirX + planeX * cameraX;
                final var rayDirY = dirY + planeY * cameraX;

                var mapX = (int) posX;
                var mapY = (int) posY;
                final var deltaDistX = Math.abs(1 / rayDirX);
                final var deltaDistY = Math.abs(1 / rayDirY);

                var sideDistX = 0.0;
                var sideDistY = 0.0;
                var stepX = 0;
                var stepY = 0;
                var hit = 0;
                var side = 0;

                // Calculate step and initial sideDist
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

                // DDA algorithm (Digital Differential Analysis) to find wall intersection
                while (hit == 0) {
                    if (sideDistX < sideDistY) {
                        sideDistX += deltaDistX;
                        mapX += stepX;
                        side = 0;
                    } else {
                        sideDistY += deltaDistY;
                        mapY += stepY;
                        side = 1;
                    }
                    if (worldMap[mapX][mapY] > 0) {
                        hit = 1;
                    }
                }

                // Calculate distance projected on camera direction (prevents fisheye effect)
                final var perpWallDist = (side == 0) ? (sideDistX - deltaDistX) : (sideDistY - deltaDistY);
                final var lineHeight = (int) (height / perpWallDist);
                // Calculate lowest and highest pixel to fill in current stripe
                final var drawStart = Math.max(0, -lineHeight / 2 + height / 2);
                final var drawEnd = Math.min(height - 1, lineHeight / 2 + height / 2);

                // Calculate where exactly the wall was hit for texture mapping
                var wallX = (side == 0) ? posY + perpWallDist * rayDirY : posX + perpWallDist * rayDirX;
                wallX -= Math.floor(wallX);

                // Internal texture scaling
                final var texX = (int) (wallX * 128.0);
                final var distIntensity = Math.clamp(1.4 / (1.0 + perpWallDist * 0.8), 0.1, 1.0);
                final var thickComp = (int) Math.max(1, perpWallDist / 2.0);

                // Procedural Masonry Hash (determines irregular stone pattern and tone)
                final var wallSeed = (mapX * 59 + mapY * 43);

                // Draw the vertical wall slice
                for (var y = drawStart; y <= drawEnd; y++) {
                    // Primitive perspective texture mapping
                    final var texY = (int) (128.0 * (y - (-lineHeight / 2.0 + height / 2.0)) / lineHeight);

                    // Procedural Castle Stone Logic (irregular sizing)
                    final var rowHeight = 24 + (wallSeed % 8);
                    final var stoneRow = texY / rowHeight;
                    final var rowOffset = (wallSeed ^ (stoneRow * 97)) & 63;

                    final var isHorizontalMortar = (texY % rowHeight < thickComp);
                    final var isVerticalMortar = ((texX + rowOffset) % 48 < thickComp);

                    final var isMortar = isHorizontalMortar || isVerticalMortar;
                    final var shadeFactor = (1.0 - ((double) (y - drawStart) / (drawEnd - drawStart + 1) * 0.5)) * distIntensity;

                    if (isMortar) {
                        // Render mortar color (variable shading based on distance)
                        final var m = (int) (60 * shadeFactor);
                        g2d.setColor(new Color(m, (int) (m * 0.9), (int) (m * 0.8)));
                    } else {
                        // Determine base stone color from earthy palette (rotate based on hash)
                        final var stoneHash = (wallSeed ^ stoneRow ^ ((texX + rowOffset) / 48)) & 3;
                        var r = 0;
                        var g = 0;
                        var b = 0;
                        switch (stoneHash) {
                            case 0 -> {
                                r = 50;
                                g = 40;
                                b = 30;
                            }  // *** Deep Espresso (Darkened Variation) ***
                            case 1 -> {
                                r = 110;
                                g = 100;
                                b = 85;
                            } // Tan
                            case 2 -> {
                                r = 80;
                                g = 80;
                                b = 85;
                            }   // Grey
                            default -> {
                                r = 70;
                                g = 60;
                                b = 50;
                            }  // Deep Shadow
                        }

                        // Apply procedural pixel noise for texture
                        final var grain = ((texX ^ texY) & 7) * 4;
                        r = (int) Math.clamp((r - grain) * shadeFactor, 0, 255);
                        g = (int) Math.clamp((g - grain) * shadeFactor, 0, 255);
                        b = (int) Math.clamp((b - grain) * shadeFactor, 0, 255);

                        // Darken side walls for simple lighting effect
                        if (side == 1) {
                            r /= 1.4;
                            g /= 1.4;
                            b /= 1.4;
                        }
                        g2d.setColor(new Color(r, g, b));
                    }
                    // Drawing directly to the buffered graphics (cached in Base)
                    g2d.drawLine(x, y, x, y);
                }
            }

            // High-performance, zero-allocation transfer of the BufferedImage to OLED hardware via FFM
            oled.drawImage(getImage());

            // --- Navigation Logic (Autonomous Walk) ---
            final var moveSpeed = 0.08;
            final var rotSpeed = 0.06;
            // Buffer to prevent zooming directly into a brick wall texture
            final var wallBuffer = 0.3;

            // Decision state machine for AI navigation
            if (stateTicks <= 0) {
                final var roll = random.nextInt(10);
                if (roll < 6) {
                    moveState = 0;      // 60% chance: Move Forward
                } else if (roll < 7) {
                    moveState = 1;      // 10% chance: Move Backward
                } else if (roll < 9) {
                    moveState = 2;      // 20% chance: Turn Left
                } else {
                    moveState = 3;      // 10% chance: Turn Right
                }
                // Randomize duration of the current movement state
                stateTicks = 10 + random.nextInt(25);
            }

            // Execute movement based on state
            switch (moveState) {
                case 0 -> {
                    // Forward movement with wall-buffer check (prevents "stupid" close-ups)
                    if (worldMap[(int) (posX + dirX * (moveSpeed + wallBuffer))][(int) posY] == 0) {
                        posX += dirX * moveSpeed;
                    } else {
                        stateTicks = 0; // Force a state change immediately if we hit the proximity gap
                    }
                    if (worldMap[(int) posX][(int) (posY + dirY * (moveSpeed + wallBuffer))] == 0) {
                        posY += dirY * moveSpeed;
                    } else {
                        stateTicks = 0;
                    }
                }
                case 1 -> {
                    // Move Backward
                    if (worldMap[(int) (posX - dirX * moveSpeed)][(int) posY] == 0) {
                        posX -= dirX * moveSpeed;
                    }
                    if (worldMap[(int) posX][(int) (posY - dirY * moveSpeed)] == 0) {
                        posY -= dirY * moveSpeed;
                    }
                }
                case 2, 3 -> {
                    // Perform Rotation (Left or Right)
                    final var rot = (moveState == 2) ? 1.0 : -1.0;
                    final var oldDirX = dirX;
                    dirX = dirX * Math.cos(rot * rotSpeed) - dirY * Math.sin(rot * rotSpeed);
                    dirY = oldDirX * Math.sin(rot * rotSpeed) + dirY * Math.cos(rot * rotSpeed);
                    final var oldPlaneX = planeX;
                    planeX = planeX * Math.cos(rot * rotSpeed) - planeY * Math.sin(rot * rotSpeed);
                    planeY = oldPlaneX * Math.sin(rot * rotSpeed) + planeY * Math.cos(rot * rotSpeed);
                }
            }
            stateTicks--;

            // Simple target FPS enforcement
            final var diff = System.currentTimeMillis() - startTime;
            if (diff < frameDelay) {
                try {
                    TimeUnit.MILLISECONDS.sleep(frameDelay - diff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Entry point for CLI execution via Picocli.
     * <p>
     * Detects user FPS preference and initializes the Base class resources before starting the main loop.
     * </p>
     *
     * @return Exit code.
     * @throws Exception possible hardware or foreign memory exception.
     */
    @Override
    public Integer call() throws Exception {
        // Use Picocli to determine if FPS was set explicitly via CLI option
        if (!spec.commandLine().getParseResult().hasMatchedOption("fps")) {
            setFps(30); // Default override to 30 FPS for optimal performance/smoothness ratio
        }
        // super.call() initializes GPIO/SPI and creates the main FFM transfer segment/graphics context
        super.call();
        runDemo(getOled());
        done(); // Finalizes hardware state
        return 0;
    }

    /**
     * Main method.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new Raytrace()).execute(args));
    }
}
