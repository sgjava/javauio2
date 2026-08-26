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
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * High-performance image viewer demo that automatically scales and centers PNG or JPEG images loaded from the classpath root or
 * local file system to fit any color display.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(
        name = "ImageViewer",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "Auto-scaling and centering image viewer supporting classpath resources and files."
)
public class ImageViewer extends Base {

    /**
     * Path or resource name of the image file to load and display.
     */
    @Option(
            names = {"--file"},
            description = "Path or classpath resource name to image file (PNG or JPEG).",
            defaultValue = "/240x320.jpg"
    )
    private String imagePath;

    /**
     * Primary loop for the demonstration.
     *
     * @param display The hardware color display device instance.
     * @throws Exception If communication fails or image cannot be loaded.
     */
    public final void runDemo(final AbstractColorDisplay display) throws Exception {
        log.info("Starting Image Viewer Demo with resource/file: {}", imagePath);

        final var displayWidth = display.getWidth();
        final var displayHeight = display.getHeight();

        // 1. Try loading image from classpath root first, then fall back to local file system
        BufferedImage sourceImage = null;

        final var resourcePath = imagePath.startsWith("/") ? imagePath : "/" + imagePath;
        try (final var inputStream = ImageViewer.class.getResourceAsStream(resourcePath)) {
            if (inputStream != null) {
                sourceImage = ImageIO.read(inputStream);
                log.info("Loaded image successfully from classpath: {}", resourcePath);
            }
        } catch (final Exception e) {
            log.debug("Could not load from classpath, trying file system: {}", e.getMessage());
        }

        // Fallback to local file system if classpath lookup missed
        if (sourceImage == null) {
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
                sourceImage = ImageIO.read(file);
                log.info("Loaded image successfully from file system: {}", file.getAbsolutePath());
            }
        }

        if (sourceImage == null) {
            throw new IllegalArgumentException("Image could not be found in classpath or file system: " + imagePath);
        }

        // 2. Compute scaled dimensions maintaining aspect ratio to fit display
        final var srcWidth = sourceImage.getWidth();
        final var srcHeight = sourceImage.getHeight();

        var targetWidth = displayWidth;
        var targetHeight = displayHeight;

        final var scaleX = (double) displayWidth / srcWidth;
        final var scaleY = (double) displayHeight / srcHeight;
        final var scale = Math.min(scaleX, scaleY);

        targetWidth = (int) (srcWidth * scale);
        targetHeight = (int) (srcHeight * scale);

        final var xOffset = (displayWidth - targetWidth) / 2;
        final var yOffset = (displayHeight - targetHeight) / 2;

        // 3. Prepare drawing canvas (Buffered image matching display dimensions)
        final var bi = new BufferedImage(displayWidth, displayHeight, BufferedImage.TYPE_INT_ARGB);
        final var g2d = bi.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Fill background with black letterboxing bars
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, displayWidth, displayHeight);

        // Draw scaled and centered image
        g2d.drawImage(sourceImage, xOffset, yOffset, targetWidth, targetHeight, null);
        g2d.dispose();

        // 4. Prepare zero-allocation RGB565 byte buffer
        final var bufferSize = displayWidth * displayHeight * 2;
        final var rgb565Buffer = new byte[bufferSize];
        final var frameSeg = MemorySegment.ofArray(rgb565Buffer);

        // Convert once since it's a static image viewer
        convertArgbToRgb565(bi, rgb565Buffer, displayWidth, displayHeight);

        log.info("Displaying image (Original: {}x{}, Scaled: {}x{}, Offset: {}, {})",
                srcWidth, srcHeight, targetWidth, targetHeight, xOffset, yOffset);

        while (isRunning() && !Thread.currentThread().isInterrupted()) {
            final var startTime = System.currentTimeMillis();

            // Blast frame buffer to hardware display via FFM
            display.setWindow(0, 0, displayWidth, displayHeight);
            MemorySegment.copy(frameSeg, ValueLayout.JAVA_BYTE, 0, display.getImageSegment(), ValueLayout.JAVA_BYTE, 0, bufferSize);
            display.writeData(display.getImageSegment());

            // Throttle loop frame rate
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
    }

    /**
     * Converts an ARGB BufferedImage into an RGB565 byte array.
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
        System.exit(new CommandLine(new ImageViewer()).execute(args));
    }
}
