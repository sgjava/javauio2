/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * C64 Wizard of Wor multi-sprite arcade simulation demo configured for 18x18 character cells, with adaptive scaling (1x for small
 * displays like SSD1331, 2x for larger 240x320 displays).
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(
        name = "SpriteSheet",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "C64 18x18 adaptive-scaled multi-sprite simulation with strict 4-way cardinal movement."
)
public class SpriteSheet extends Base {

    /**
     * Path or resource name of the sprite sheet file.
     */
    @Option(
            names = {"--file"},
            description = "Path or classpath resource name to sprite sheet image.",
            defaultValue = "/wow.png"
    )
    private String imagePath;

    /**
     * Width of an individual sprite cell in pixels (18).
     */
    @Option(
            names = {"--sprite-width"},
            description = "Width of a single sprite cell.",
            defaultValue = "18"
    )
    private int spriteWidth;

    /**
     * Height of an individual sprite cell in pixels (18).
     */
    @Option(
            names = {"--sprite-height"},
            description = "Height of a single sprite cell.",
            defaultValue = "18"
    )
    private int spriteHeight;

    /**
     * Number of active simultaneous sprites on screen.
     */
    @Option(
            names = {"--sprite-count"},
            description = "Number of active sprites in simulation.",
            defaultValue = "6"
    )
    private int spriteCount;

    /**
     * Optional manual scaling factor override (0 = auto-detect based on resolution).
     */
    @Option(
            names = {"--scale"},
            description = "Sprite scale factor (0 for auto-detect).",
            defaultValue = "0"
    )
    private int scaleOverride;

    /**
     * Internal entity representation for C64 multi-sprite tracking.
     */
    private static class SpriteEntity {

        double x, y;
        double dx, dy;
        int characterRow;      // Row 0 to 5 (one game character per row)
        int direction;         // 0 = Left, 1 = Right, 2 = Up, 3 = Down
        int animFrame;         // 0 to 2 (3-sprite walking sequence)
        int frameCounter;
        int moveTimer;         // Timer to periodically change cardinal direction
    }

    /**
     * Primary loop for the demonstration.
     *
     * @param display The hardware color display device instance.
     * @throws Exception If communication fails or image cannot be loaded.
     */
    public final void runDemo(final AbstractColorDisplay display) throws Exception {
        log.info("Starting C64 Wizard of Wor Adaptive Scale Demo with asset: {}", imagePath);

        final var displayWidth = display.getWidth();
        final var displayHeight = display.getHeight();

        // 1. Try loading sprite sheet from classpath root first, then fall back to local file system
        BufferedImage spriteSheet = null;
        final var resourcePath = imagePath.startsWith("/") ? imagePath : "/" + imagePath;

        try (final var inputStream = SpriteSheet.class.getResourceAsStream(resourcePath)) {
            if (inputStream != null) {
                spriteSheet = ImageIO.read(inputStream);
                log.info("Loaded sprite sheet successfully from classpath: {}", resourcePath);
            }
        } catch (final Exception e) {
            log.debug("Could not load from classpath, trying file system: {}", e.getMessage());
        }

        if (spriteSheet == null) {
            var file = new File(imagePath);
            if (!file.exists()) {
                file = new File("demo2/" + imagePath);
            }
            if (!file.exists() && imagePath.startsWith("/")) {
                file = new File(imagePath.substring(1));
            }
            if (!file.exists()) {
                file = new File("demo2/src/main/resources" + imagePath);
            }

            if (file.exists()) {
                spriteSheet = ImageIO.read(file);
                log.info("Loaded sprite sheet successfully from file system: {}", file.getAbsolutePath());
            }
        }

        if (spriteSheet == null) {
            throw new IllegalArgumentException("Sprite sheet could not be found: " + imagePath);
        }

        // 2. Determine scale factor: use override if specified, otherwise auto-select based on resolution
        final int scale;
        if (scaleOverride > 0) {
            scale = scaleOverride;
        } else {
            // Use 1x for low-res displays (like SSD1331 96x64), 2x for larger displays (like 240x320)
            scale = (displayWidth <= 160 || displayHeight <= 128) ? 1 : 2;
        }

        final var sWidth = spriteWidth * scale;
        final var sHeight = spriteHeight * scale;
        log.info("Display dimensions: {}x{}, selected sprite scale: {}x (effective size: {}x{}px)",
                displayWidth, displayHeight, scale, sWidth, sHeight);

        // 3. Initialize active entities
        final var random = new Random();
        final var entities = new SpriteEntity[spriteCount];

        for (var i = 0; i < spriteCount; i++) {
            final var entity = new SpriteEntity();
            entity.x = random.nextInt(Math.max(1, displayWidth - sWidth));
            entity.y = random.nextInt(Math.max(1, displayHeight - sHeight));

            setRandomCardinalDirection(entity, random);

            entity.characterRow = i % 6;
            entity.animFrame = random.nextInt(3);
            entity.moveTimer = 50 + random.nextInt(100);
            entities[i] = entity;
        }

        // 4. Prepare drawing canvas (use Base snapshot internal image buffer directly if available to prevent allocations)
        final BufferedImage bi = getImage() != null ? getImage() : new BufferedImage(displayWidth, displayHeight,
                BufferedImage.TYPE_INT_ARGB);
        final var g2d = bi.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        final var bufferSize = displayWidth * displayHeight * 2;
        final var rgb565Buffer = new byte[bufferSize];
        final var frameSeg = MemorySegment.ofArray(rgb565Buffer);

        log.info("Rendering simulation loop...");

        while (isRunning() && !Thread.currentThread().isInterrupted()) {
            final var startTime = System.currentTimeMillis();

            // Clear background to retro arcade black
            g2d.setColor(Color.BLACK);
            g2d.fillRect(0, 0, displayWidth, displayHeight);

            // Draw HUD Header (skip or shorten if display is tiny like SSD1331)
            if (displayWidth >= 160 && displayHeight >= 128) {
                g2d.setColor(Color.YELLOW);
                g2d.drawString("WIZARD OF WOR", 10, 15);
                g2d.setColor(Color.CYAN);
                g2d.drawString("HI: 99990", displayWidth - 75, 15);
            }

            // Update and render each sprite entity
            for (final var entity : entities) {
                entity.x += entity.dx;
                entity.y += entity.dy;

                boolean bounced = false;

                // Boundary checks triggering random redirection upon edge hit
                final int topMargin = (displayWidth >= 160 && displayHeight >= 128) ? 25 : 2;
                if (entity.x <= 2) {
                    entity.x = 2;
                    setRandomCardinalDirection(entity, random);
                    bounced = true;
                } else if (entity.x >= displayWidth - sWidth - 2) {
                    entity.x = displayWidth - sWidth - 2;
                    setRandomCardinalDirection(entity, random);
                    bounced = true;
                }

                if (entity.y <= topMargin) {
                    entity.y = topMargin;
                    setRandomCardinalDirection(entity, random);
                    bounced = true;
                } else if (entity.y >= displayHeight - sHeight - 2) {
                    entity.y = displayHeight - sHeight - 2;
                    setRandomCardinalDirection(entity, random);
                    bounced = true;
                }

                if (!bounced && entity.moveTimer <= 0) {
                    setRandomCardinalDirection(entity, random);
                    entity.moveTimer = 60 + random.nextInt(100);
                }
                entity.moveTimer--;

                // Cycle through the 3-sprite walking sequence frames (0, 1, 2)
                entity.frameCounter++;
                if (entity.frameCounter >= 6) {
                    entity.frameCounter = 0;
                    entity.animFrame = (entity.animFrame + 1) % 3;
                }

                final var baseCol = entity.direction * 3;
                final var col = baseCol + entity.animFrame;
                final var srcX = col * spriteWidth;
                final var srcY = entity.characterRow * spriteHeight;

                if (srcX + spriteWidth <= spriteSheet.getWidth() && srcY + spriteHeight <= spriteSheet.getHeight()) {
                    final var subSprite = spriteSheet.getSubimage(srcX, srcY, spriteWidth, spriteHeight);

                    // Draw sprite with selected scale factor, skipping black background pixels
                    drawTransparentSprite(subSprite, bi, (int) entity.x, (int) entity.y, scale);
                }
            }

            // Convert ARGB frame to RGB565 byte array efficiently
            convertArgbToRgb565(bi, rgb565Buffer, displayWidth, displayHeight);

            // Blast frame buffer to hardware display via FFM
            display.setWindow(0, 0, displayWidth, displayHeight);
            MemorySegment.copy(frameSeg, ValueLayout.JAVA_BYTE, 0, display.getImageSegment(), ValueLayout.JAVA_BYTE, 0, bufferSize);
            display.writeData(display.getImageSegment());

            // Frame rate pacing / throttling
            final var elapsedTime = System.currentTimeMillis() - startTime;
            final var targetDelay = Math.max(1, 1000 / getFps());

            if (elapsedTime < targetDelay) {
                try {
                    TimeUnit.MILLISECONDS.sleep(targetDelay - elapsedTime);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.debug("Interrupted during sleep, exiting loop.");
                    break;
                }
            }
        }
        g2d.dispose();
    }

    /**
     * Assigns a strict cardinal direction (Left, Right, Up, Down) ensuring zero diagonal motion.
     *
     * @param entity The sprite entity to update.
     * @param random Random generator instance.
     */
    private void setRandomCardinalDirection(final SpriteEntity entity, final Random random) {
        final var choice = random.nextInt(4);
        final var speed = 1.5;
        switch (choice) {
            case 0 -> { // Left
                entity.dx = -speed;
                entity.dy = 0;
                entity.direction = 0;
            }
            case 1 -> { // Right
                entity.dx = speed;
                entity.dy = 0;
                entity.direction = 1;
            }
            case 2 -> { // Up
                entity.dx = 0;
                entity.dy = -speed;
                entity.direction = 2;
            }
            case 3 -> { // Down
                entity.dx = 0;
                entity.dy = speed;
                entity.direction = 3;
            }
            default -> {
            }
        }
    }

    /**
     * Draws a sprite onto the destination image with custom scaling while skipping pure black pixels.
     *
     * @param src Source sprite subimage.
     * @param dest Destination target BufferedImage.
     * @param destX Target screen X coordinate.
     * @param destY Target screen Y coordinate.
     * @param scale Integer scaling factor.
     */
    private void drawTransparentSprite(final BufferedImage src, final BufferedImage dest, final int destX, final int destY,
            final int scale) {
        final var srcWidth = src.getWidth();
        final var srcHeight = src.getHeight();
        final var canvasWidth = dest.getWidth();
        final var canvasHeight = dest.getHeight();

        for (var sy = 0; sy < srcHeight; sy++) {
            final var targetY = destY + (sy * scale);
            if (targetY >= canvasHeight) {
                break;
            }

            for (var sx = 0; sx < srcWidth; sx++) {
                final var targetX = destX + (sx * scale);
                if (targetX >= canvasWidth) {
                    break;
                }

                final var argb = src.getRGB(sx, sy);
                // Color-key transparency: skip black pixels
                if ((argb & 0x00FFFFFF) != 0x00000000) {
                    if (scale == 1) {
                        if (targetX < canvasWidth && targetY < canvasHeight && targetX >= 0 && targetY >= 0) {
                            dest.setRGB(targetX, targetY, argb);
                        }
                    } else {
                        // Draw scaled block (nearest-neighbor replication)
                        for (var dy = 0; dy < scale; dy++) {
                            for (var dx = 0; dx < scale; dx++) {
                                final var px = targetX + dx;
                                final var py = targetY + dy;
                                if (px < canvasWidth && py < canvasHeight && px >= 0 && py >= 0) {
                                    dest.setRGB(px, py, argb);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Converts an ARGB BufferedImage into an RGB565 byte array using a preallocated buffer.
     *
     * @param bi Source image.
     * @param dest Destination byte array.
     * @param width Image width.
     * @param height Image height.
     */
    private void convertArgbToRgb565(final BufferedImage bi, final byte[] dest, final int width, final int height) {
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
     * Command entry point.
     *
     * @return Process exit code.
     * @throws Exception If an error occurs during runtime.
     */
    @Override
    public final Integer call() throws Exception {
        super.call();
        try {
            runDemo((AbstractColorDisplay) getDisplay());
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
        System.exit(new CommandLine(new SpriteSheet()).execute(args));
    }
}
