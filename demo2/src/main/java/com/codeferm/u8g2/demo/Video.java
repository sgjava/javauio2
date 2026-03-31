/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2.demo;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.u8g2.U8g2;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Video demo with resolution-aware centering and clipping using FFM.
 * <p>
 * Optimized for zero-allocation playback by pre-caching the ByteBuffer wrapper 
 * and using a persistent native buffer.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Video", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Video demo with resolution-independent centering")
public class Video extends Base {

    /**
     * Input file path for the raw video data.
     */
    @Option(names = {"-f", "--file"}, description = "Input video file name, ${DEFAULT-VALUE} by default.")
    private String fileName = "src/main/resources/video.raw";

    /**
     * Target frames per second for playback pacing.
     */
    @Option(names = {"-fps", "--fps"}, description = "Target FPS, ${DEFAULT-VALUE} by default.", defaultValue = "24")
    private int fps;

    /**
     * The width of the source video in pixels.
     */
    @Option(names = {"--vw"}, description = "Video width, ${DEFAULT-VALUE} by default.", defaultValue = "128")
    private int videoWidth;

    /**
     * The height of the source video in pixels.
     */
    @Option(names = {"--vh"}, description = "Video height, ${DEFAULT-VALUE} by default.", defaultValue = "64")
    private int videoHeight;

    /**
     * Implementation of the Base run method.
     *
     * @param u8g2 MemorySegment handle to the u8g2 structure.
     */
    @Override
    protected void run(final MemorySegment u8g2) {
        final var displayW = getWidth();
        final var displayH = getHeight();
        // Calculate frame size in bytes (1 bit per pixel)
        final int frameSize = (videoHeight * videoWidth) / 8;
        final long frameDurationNs = TimeUnit.SECONDS.toNanos(1) / fps;

        // Determine offsets to center the video
        final int offsetX = (displayW - videoWidth) / 2;
        final int offsetY = (displayH - videoHeight) / 2;

        log.info("Display: {}x{}, Video: {}x{}. Centering at offset: {},{}",
                displayW, displayH, videoWidth, videoHeight, offsetX, offsetY);

        // Pre-cache path to avoid object creation in the loop
        final var videoPath = Path.of(fileName);

        // Use a confined arena for the frame buffer
        try (var localArena = Arena.ofConfined()) {
            final var image = localArena.allocate(frameSize);
            
            // CACHE the ByteBuffer wrapper outside the loop.
            // Calling image.asByteBuffer() inside the loop creates a new Heap object every frame.
            final ByteBuffer frameBuffer = image.asByteBuffer();

            try (var channel = FileChannel.open(videoPath, StandardOpenOption.READ)) {
                // Read directly from the channel into our cached native ByteBuffer wrapper
                while (channel.read(frameBuffer) != -1) {
                    final long startTime = System.nanoTime();

                    // Clear the buffer to prevent tiling/artifacts on larger screens
                    U8g2.u8g2_ClearBuffer(u8g2);

                    // Draw centered (or clipped) bitmap
                    // u8g2_DrawBitmap(u8g2, x, y, cnt, h, bitmap)
                    // cnt is width in bytes: videoWidth / 8
                    U8g2.u8g2_DrawBitmap(u8g2, (short) offsetX, (short) offsetY,
                            (short) (videoWidth / 8), (short) videoHeight, image);

                    U8g2.u8g2_SendBuffer(u8g2);
                    
                    // Reset position for next read without re-allocating the wrapper
                    frameBuffer.clear();

                    // Maintain target FPS
                    final long elapsedTime = System.nanoTime() - startTime;
                    final long sleepTimeNs = frameDurationNs - elapsedTime;
                    if (sleepTimeNs > 0) {
                        TimeUnit.NANOSECONDS.sleep(sleepTimeNs);
                    }
                }
            } catch (IOException | InterruptedException e) {
                log.error("Error during video playback: {}", e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        log.info("Video playback complete.");
    }

    /**
     * Main entry point with automatic type conversion.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new Video()).execute(args));
    }
}
