/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.ssd1331.demo;

import com.codeferm.periphery.device.Ssd1331;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * High-performance video playback demo using FFM and intelligent resource resolution.
 * <p>
 * Optimized for ARM64 deployment. The demo defaults to internal JAR resources but allows for filesystem overrides. Uses
 * zero-allocation native segments for SPI transfers.
 * </p>
 *
 * <p>
 * ffmpeg -i input.mp4 -f rawvideo -pix_fmt rgb565be -s 96x64 video.raw
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Video", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Play raw RGB565BE video frames using FFM segments")
public class Video extends Base {

    /**
     * Input file path for the raw video data. Defaulted to resource name for JAR deployment.
     */
    @Option(names = {"--file"}, description = "Input RGB565BE file, ${DEFAULT-VALUE} by default.")
    private String fileName = "color.raw";

    /**
     * Executes the video playback loop using FFM and SPI.
     * <p>
     * Reuses buffers to avoid allocation on each frame.
     * </p>
     *
     * @return Exit code.
     * @throws Exception If SPI or I/O operations fail.
     */
    @Override
    public Integer call() throws Exception {
        super.call();

        final var frameSize = getWidth() * getHeight() * 2;

        // Optimization: Re-use heap buffer for I/O to avoid GC thrashing
        final var heapBuffer = new byte[frameSize];

        log.info("Starting video playback: {} ({} FPS)", fileName, getFps());

        // Create a local confined arena for the demo frame lifecycle execution path
        try (final var localArena = Arena.ofConfined(); final var inputStream = getInputStream()) {

            // Optimization: Allocate native buffer in the local Arena once
            final var frameBuffer = localArena.allocate(frameSize);

            // Set SSD1331 address window for full-screen update
            getOled().writeCommand(new byte[]{
                Ssd1331.SET_COLUMN_ADDRESS, 0, (byte) (getWidth() - 1),
                Ssd1331.SET_ROW_ADDRESS, 0, (byte) (getHeight() - 1)
            });

            final var frameDurationNs = TimeUnit.SECONDS.toNanos(1) / getFps();
            var frameCount = 0;
            var bytesRead = 0;

            final var startTime = System.nanoTime();

            // readNBytes is critical to ensure we have a complete frame before SPI transfer
            while ((bytesRead = inputStream.readNBytes(heapBuffer, 0, frameSize)) == frameSize) {
                final var nextFrameTime = startTime + (frameCount * frameDurationNs);

                // Zero-allocation copy from heap to native segment
                MemorySegment.copy(heapBuffer, 0, frameBuffer, ValueLayout.JAVA_BYTE, 0, frameSize);

                // Native SPI transfer
                getOled().writeData(frameBuffer);

                frameCount++;

                // FPS Sync
                final var currentTime = System.nanoTime();
                final var sleepTime = nextFrameTime - currentTime;
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime / 1_000_000, (int) (sleepTime % 1_000_000));
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
        // Check ClassLoader for resource URL
        final var resourceUrl = getClass().getClassLoader().getResource(fileName);

        if (resourceUrl != null) {
            log.info("Resource found: {}", resourceUrl);
            return new BufferedInputStream(resourceUrl.openStream());
        }

        // Fallback to absolute/relative path on filesystem
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
    public static void main(final String[] args) {
        final var exitCode = new CommandLine(new Video()).execute(args);
        System.exit(exitCode);
    }
}
