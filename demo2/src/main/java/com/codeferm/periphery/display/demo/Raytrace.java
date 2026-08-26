/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * High-performance 3D Raycasting demo featuring irregular "Castle Ashlar" stone facades using FFM, a producer-consumer background
 * rendering thread, and direct pixel manipulation, fully adaptable to multiple display resolutions.
 * <p>
 * This engine renders an old-school dungeon environment. It uses coordinate-based hashing to generate irregular stone sizes and
 * varying earthy tones. The navigation system uses a weighted random-walk with collision-avoidance to prevent the player from
 * getting stuck against wall textures.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.1.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Raytrace", mixinStandardHelpOptions = true, version = "1.1.0-SNAPSHOT",
        description = "Castle raycaster with multi-threaded producer-consumer pipeline and direct pixel rendering")
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
     * Simple container for player position and camera vectors to satisfy lambda effectively-final requirements.
     */
    private static class PlayerState {

        double posX;
        double posY;
        double dirX;
        double dirY;
        double planeX;
        double planeY;

        PlayerState(final double posX, final double posY, final double dirX, final double dirY, final double planeX,
                final double planeY) {
            this.posX = posX;
            this.posY = posY;
            this.dirX = dirX;
            this.dirY = dirY;
            this.planeX = planeX;
            this.planeY = planeY;
        }
    }

    /**
     * Main rendering and exploration loop using a producer-consumer pattern.
     * <p>
     * Decouples frame calculation (background worker thread) from display transmission (main thread) and uses direct integer buffer
     * manipulation to eliminate object allocation overhead.
     * </p>
     *
     * @param display Abstract color display driver instance used for foreign memory transfers.
     */
    public final void runDemo(final AbstractColorDisplay display) {
        final var width = getWidth();
        final var height = getHeight();

        // Initialize player state container
        final var player = new PlayerState(4.5, 1.5, -1.0, 0.0, 0.0, 0.66);

        // Navigation state
        var moveState = 0; // 0: Forward, 1: Back, 2: Left, 3: Right
        var stateTicks = 0;

        final var frameDelay = 1000 / getFps();
        log.info("Starting Castle Exploration via FFM (Producer-Consumer) at {} FPS with resolution {}x{}", getFps(), width, height);

        // Double-buffering queue to decouple frame generation from output
        final BlockingQueue<BufferedImage> frameQueue = new ArrayBlockingQueue<>(2);

        // Pre-allocate dual buffers for zero-allocation handoff
        final BufferedImage bufferA = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final BufferedImage bufferB = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // Fetch base snapshot handler image buffer if snapshot flag is enabled
        final BufferedImage snapshotBi = getImage();

        // Background Producer Thread (Raycasting & Pixel Rendering)
        final Thread renderThread = new Thread(() -> {
            var activeBuffer = bufferA;
            while (!Thread.currentThread().isInterrupted() && isRunning()) {
                // Snapshot player state locally for this frame render pass
                final var curPosX = player.posX;
                final var curPosY = player.posY;
                final var curDirX = player.dirX;
                final var curDirY = player.dirY;
                final var curPlaneX = player.planeX;
                final var curPlaneY = player.planeY;

                final var pixels = ((DataBufferInt) activeBuffer.getRaster().getDataBuffer()).getData();

                // Clear ceiling (Sky/Atmosphere) and floor using direct pixel array loops
                final var ceilingColor = (40 << 16) | (35 << 8) | 30;
                final var floorColor = (60 << 16) | (55 << 8) | 50;
                final var halfArea = (width * height) / 2;

                for (var i = 0; i < halfArea; i++) {
                    pixels[i] = ceilingColor;
                }
                for (var i = halfArea; i < pixels.length; i++) {
                    pixels[i] = floorColor;
                }

                // Raycasting loop
                for (var x = 0; x < width; x++) {
                    final var cameraX = 2.0 * x / (double) width - 1.0;
                    final var rayDirX = curDirX + curPlaneX * cameraX;
                    final var rayDirY = curDirY + curPlaneY * cameraX;

                    var mapX = (int) curPosX;
                    var mapY = (int) curPosY;
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
                        sideDistX = (curPosX - mapX) * deltaDistX;
                    } else {
                        stepX = 1;
                        sideDistX = (mapX + 1.0 - curPosX) * deltaDistX;
                    }
                    if (rayDirY < 0) {
                        stepY = -1;
                        sideDistY = (curPosY - mapY) * deltaDistY;
                    } else {
                        stepY = 1;
                        sideDistY = (mapY + 1.0 - curPosY) * deltaDistY;
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
                    final var drawStart = Math.max(0, -lineHeight / 2 + height / 2);
                    final var drawEnd = Math.min(height - 1, lineHeight / 2 + height / 2);

                    // Calculate where exactly the wall was hit for texture mapping
                    var wallX = (side == 0) ? curPosY + perpWallDist * rayDirY : curPosX + perpWallDist * rayDirX;
                    wallX -= Math.floor(wallX);

                    // Internal texture scaling
                    final var texX = (int) (wallX * 128.0);
                    final var distIntensity = Math.clamp(1.8 / (1.0 + perpWallDist * 0.4), 0.3, 1.0);
                    final var thickComp = (int) Math.max(1, perpWallDist / 2.0);

                    // Procedural Masonry Hash (determines irregular stone pattern and tone)
                    final var wallSeed = (mapX * 59 + mapY * 43);

                    // Draw the vertical wall slice directly into pixel array
                    for (var y = drawStart; y <= drawEnd; y++) {
                        final var texY = (int) (128.0 * (y - (-lineHeight / 2.0 + height / 2.0)) / lineHeight);

                        final var rowHeight = 24 + (wallSeed % 8);
                        final var stoneRow = texY / rowHeight;
                        final var rowOffset = (wallSeed ^ (stoneRow * 97)) & 63;

                        final var isHorizontalMortar = (texY % rowHeight < thickComp);
                        final var isVerticalMortar = ((texX + rowOffset) % 48 < thickComp);
                        final var isMortar = isHorizontalMortar || isVerticalMortar;
                        final var shadeFactor = (1.0 - ((double) (y - drawStart) / (drawEnd - drawStart + 1) * 0.3)) * distIntensity;

                        int rgb;
                        if (isMortar) {
                            final var m = (int) (110 * shadeFactor);
                            rgb = (m << 16) | ((int) (m * 0.95) << 8) | (int) (m * 0.9);
                        } else {
                            final var stoneHash = (wallSeed ^ stoneRow ^ ((texX + rowOffset) / 48)) & 3;
                            var r = 0;
                            var g = 0;
                            var b = 0;
                            switch (stoneHash) {
                                case 0 -> {
                                    r = 120;
                                    g = 100;
                                    b = 85;
                                }
                                case 1 -> {
                                    r = 175;
                                    g = 160;
                                    b = 140;
                                }
                                case 2 -> {
                                    r = 140;
                                    g = 140;
                                    b = 150;
                                }
                                default -> {
                                    r = 150;
                                    g = 130;
                                    b = 110;
                                }
                            }

                            final var grain = ((texX ^ texY) & 7) * 4;
                            r = (int) Math.clamp((r - grain) * shadeFactor, 0, 255);
                            g = (int) Math.clamp((g - grain) * shadeFactor, 0, 255);
                            b = (int) Math.clamp((b - grain) * shadeFactor, 0, 255);

                            if (side == 1) {
                                r = (int) (r / 1.2);
                                g = (int) (g / 1.2);
                                b = (int) (b / 1.2);
                            }
                            rgb = (r << 16) | (g << 8) | b;
                        }
                        pixels[y * width + x] = rgb;
                    }
                }

                // If snapshots are enabled, mirror the completed pixel array into the snapshot buffer safely
                if (snapshotBi != null) {
                    final var snapPixels = ((DataBufferInt) snapshotBi.getRaster().getDataBuffer()).getData();
                    System.arraycopy(pixels, 0, snapPixels, 0, pixels.length);
                }

                try {
                    frameQueue.put(activeBuffer);
                    activeBuffer = (activeBuffer == bufferA) ? bufferB : bufferA;
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "Raytrace-Worker");

        renderThread.start();

        // Main Consumer Loop (Pacing & Hardware Display Transfer)
        try {
            while (!Thread.currentThread().isInterrupted() && isRunning()) {
                final var startTime = System.currentTimeMillis();

                try {
                    final BufferedImage frame = frameQueue.poll(frameDelay, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        display.drawImage(frame, 0, 0, width, height);
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                // --- Navigation Logic (Autonomous Walk) ---
                final var moveSpeed = 0.08;
                final var rotSpeed = 0.06;
                final var wallBuffer = 0.3;

                if (stateTicks <= 0) {
                    final var roll = random.nextInt(10);
                    if (roll < 6) {
                        moveState = 0;
                    } else if (roll < 7) {
                        moveState = 1;
                    } else if (roll < 9) {
                        moveState = 2;
                    } else {
                        moveState = 3;
                    }
                    stateTicks = 10 + random.nextInt(25);
                }

                switch (moveState) {
                    case 0 -> {
                        if (worldMap[(int) (player.posX + player.dirX * (moveSpeed + wallBuffer))][(int) player.posY] == 0) {
                            player.posX += player.dirX * moveSpeed;
                        } else {
                            stateTicks = 0;
                        }
                        if (worldMap[(int) player.posX][(int) (player.posY + player.dirY * (moveSpeed + wallBuffer))] == 0) {
                            player.posY += player.dirY * moveSpeed;
                        } else {
                            stateTicks = 0;
                        }
                    }
                    case 1 -> {
                        if (worldMap[(int) (player.posX - player.dirX * moveSpeed)][(int) player.posY] == 0) {
                            player.posX -= player.dirX * moveSpeed;
                        }
                        if (worldMap[(int) player.posX][(int) (player.posY - player.dirY * moveSpeed)] == 0) {
                            player.posY -= player.dirY * moveSpeed;
                        }
                    }
                    case 2, 3 -> {
                        final var rot = (moveState == 2) ? 1.0 : -1.0;
                        final var oldDirX = player.dirX;
                        player.dirX = player.dirX * Math.cos(rot * rotSpeed) - player.dirY * Math.sin(rot * rotSpeed);
                        player.dirY = oldDirX * Math.sin(rot * rotSpeed) + player.dirY * Math.cos(rot * rotSpeed);
                        final var oldPlaneX = player.planeX;
                        player.planeX = player.planeX * Math.cos(rot * rotSpeed) - player.planeY * Math.sin(rot * rotSpeed);
                        player.planeY = oldPlaneX * Math.sin(rot * rotSpeed) + player.planeY * Math.cos(rot * rotSpeed);
                    }
                }
                stateTicks--;

                final var diff = System.currentTimeMillis() - startTime;
                if (diff < frameDelay) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(frameDelay - diff);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            renderThread.interrupt();
            try {
                renderThread.join(1000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
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
    public final Integer call() throws Exception {
        if (!spec.commandLine().getParseResult().hasMatchedOption("fps")) {
            setFps(30);
        }
        super.call();
        try {
            runDemo(getDisplay());
        } finally {
            done();
        }
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
