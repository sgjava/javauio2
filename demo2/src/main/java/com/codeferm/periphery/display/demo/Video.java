/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * High-performance video playback demo using FFM and intelligent resource resolution, adaptable to multiple resolutions and
 * centering smaller RGB565 video frames on the display.
 * <p>
 * Optimized for deployment. The demo defaults to internal JAR resources but allows for filesystem overrides. Uses zero-allocation
 * image buffering and centering for device displays.
 * </p>
 *
 * <p>
 * ffmpeg -i input.mp4 -f rawvideo -pix_fmt rgb565be -s 96x64 video.raw
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.1.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Video", mixinStandardHelpOptions = true, version = "1.1.0-SNAPSHOT",
        description = "Play raw RGB565 video frames centered on display")
public class Video extends Base {

    /**
     * Input file path for the raw video data. Defaulted to resource name for JAR deployment.
     */
    @Option(names = {"--file"}, description = "Input RGB565 file, ${DEFAULT-VALUE} by default.")
    private String fileName = "color.raw";

    /**
     * Video width option.
     */
    @Option(names = {"--video-width"}, description = "Video width, ${DEFAULT-VALUE} by default.")
    private int videoWidth = 96;

    /**
     * Video height option.
     */
    @Option(names = {"--video-height"}, description = "Video height, ${DEFAULT-VALUE} by default.")
    private int videoHeight = 64;

    /**
     * Executes the video playback loop using FFM and display.
     * <p>
     * Reuses buffers to avoid allocation on each frame.
     * </p>
     *
     * @return Exit code.
     * @throws Exception If display or I/O operations fail.
     */
    @Override
    public final Integer call() throws Exception {
        super.call();

        final var displayWidth = getWidth();
        final var displayHeight = getHeight();
        final var frameSize = videoWidth * videoHeight * 2; // RGB565 uses 2 bytes per pixel

        // Optimization: Re-use heap buffer for I/O to avoid GC thrashing
        final var heapBuffer = new byte[frameSize];

        log.info("Starting video playback: {} ({}x{}, {} FPS) on display {}x{}", fileName, videoWidth, videoHeight, getFps(),
                displayWidth, displayHeight);

        // Calculate centering offsets
        final var offsetX = Math.max(0, (displayWidth - videoWidth) / 2);
        final var offsetY = Math.max(0, (displayHeight - videoHeight) / 2);

        // Create canvas using TYPE_INT_RGB to align with St7789 driver expectations
        final var videoImage = new BufferedImage(videoWidth, videoHeight, BufferedImage.TYPE_INT_RGB);
        final var imgData = ((DataBufferInt) videoImage.getRaster().getDataBuffer()).getData();

        try (final var inputStream = getInputStream()) {
            final var frameDurationNs = TimeUnit.SECONDS.toNanos(1) / getFps();
            var frameCount = 0;
            var bytesRead = 0;

            final var startTime = System.nanoTime();
            final var display = getDisplay();

            // Clear display background once if video is smaller than screen
            if (offsetX > 0 || offsetY > 0) {
                final var bgImage = new BufferedImage(displayWidth, displayHeight, BufferedImage.TYPE_INT_RGB);
                display.drawImage(bgImage, 0, 0, displayWidth, displayHeight);
            }

            // readNBytes ensures complete frame capture before transfer
            while (isRunning() && (bytesRead = inputStream.readNBytes(heapBuffer, 0, frameSize)) == frameSize && !Thread.
                    currentThread().isInterrupted()) {
                final var nextFrameTime = startTime + (frameCount * frameDurationNs);

                // Convert RGB565 Big Endian bytes to standard RGB integer pixels
                for (var i = 0; i < imgData.length; i++) {
                    final var b1 = heapBuffer[i * 2] & 0xFF;
                    final var b2 = heapBuffer[i * 2 + 1] & 0xFF;
                    final var rgb565 = (b1 << 8) | b2;

                    // Extract RGB565 components (5 bits red, 6 bits green, 5 bits blue)
                    final var r5 = (rgb565 >> 11) & 0x1F;
                    final var g6 = (rgb565 >> 5) & 0x3F;
                    final var b5 = rgb565 & 0x1F;

                    // Scale up to 8-bit components (0-255)
                    final var r = (r5 * 527 + 23) >> 6;
                    final var g = (g6 * 259 + 33) >> 6;
                    final var b = (b5 * 527 + 23) >> 6;

                    imgData[i] = (r << 16) | (g << 8) | b;
                }

                // Draw centered frame to display
                display.drawImage(videoImage, offsetX, offsetY, videoWidth, videoHeight);

                frameCount++;

                // FPS Sync
                final var currentTime = System.nanoTime();
                final var sleepTime = nextFrameTime - currentTime;
                if (sleepTime > 0) {
                    try {
                        TimeUnit.NANOSECONDS.sleep(sleepTime);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            log.info("Video finished. Frames processed: {}", frameCount);
        } catch (final IOException e) {
            log.error("Failed to play video: {}", e.getMessage());
            return 1;
        } finally {
            done();
        }
        return 0;
    }

    /**
     * Resolves the input stream based on the fileName.
     * <p>
     * Checks for a classpath resource first (ideal for JAR deployment). If not found, attempts to open a direct filesystem path.
     * </p>
     *
     * @return A BufferedInputStream.
     * @throws IOException If the source cannot be found.
     */
    private InputStream getInputStream() throws IOException {
        final var resourceUrl = getClass().getClassLoader().getResource(fileName);
        if (resourceUrl != null) {
            log.info("Resource found: {}", resourceUrl);
            return new BufferedInputStream(resourceUrl.openStream());
        }

        final var path = Path.of(fileName);
        if (Files.exists(path)) {
            log.info("Filesystem path found: {}", path.toAbsolutePath());
            return new BufferedInputStream(Files.newInputStream(path));
        }

        throw new IOException("Unable to find resource or file: " + fileName);
    }

    /**
     * Main entry point.
     *
     * @param args CLI arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new Video()).execute(args));
    }
}
