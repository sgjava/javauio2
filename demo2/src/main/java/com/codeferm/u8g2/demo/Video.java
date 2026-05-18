/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2.demo;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.u8g2.U8g2;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Video demo with resolution-aware centering and clipping using FFM.
 * <p>
 * Optimized for zero-allocation and zero disk I/O during playback by pre-caching the entire video stream from resources directly
 * into a native memory segment. Coordinated via explicit thread containment synchronization to prevent concurrent teardown access
 * during JVM shutdown.
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
     * Input file path resource location name.
     */
    @Option(names = {"-f", "--file"}, description = "Input video file name inside resources, ${DEFAULT-VALUE} by default.",
            defaultValue = "/video.raw")
    private String fileName = "/video.raw";

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
     * Game loop control flag. Marked volatile for proper cross-thread synchronization.
     */
    private volatile boolean running = true;

    /**
     * Thread reference tracking the execution sequence for the hot loop.
     */
    private Thread executionThread;

    /**
     * Implementation of the Base run method.
     *
     * @param u8g2 MemorySegment handle to the u8g2 structure.
     */
    @Override
    protected void run(final MemorySegment u8g2) {
        final var displayW = this.getWidth();
        final var displayH = this.getHeight();

        // Calculate frame size in bytes (1 bit per pixel)
        final var frameSize = (long) (this.videoHeight * this.videoWidth) / 8;
        final var frameDurationNs = TimeUnit.SECONDS.toNanos(1) / this.fps;

        // Determine offsets to center the video
        final var offsetX = (displayW - this.videoWidth) / 2;
        final var offsetY = (displayH - this.videoHeight) / 2;

        log.info("Display: {}x{}, Video: {}x{}. Centering at offset: {},{}",
                displayW, displayH, this.videoWidth, this.videoHeight, offsetX, offsetY);

        this.executionThread = Thread.currentThread();

        // Use global arena space to avoid premature deallocation of shared layout structs
        try {
            final var videoSegment = this.loadVideoFromResource(Arena.global());
            final var totalBytes = videoSegment.byteSize();
            final var totalFrames = totalBytes / frameSize;

            log.info("Successfully cached {} frames in native memory segment. Commencing playback...", totalFrames);

            var byteOffset = 0L;

            while (this.running && !this.executionThread.isInterrupted() && (byteOffset + frameSize <= totalBytes)) {
                final var startTime = System.nanoTime();

                // Double check native state right before crossing the FFM boundary
                if (!this.running || this.executionThread.isInterrupted()) {
                    break;
                }

                // Clear the back buffer structure
                U8g2.u8g2_ClearBuffer(u8g2);

                // Slice out the current frame data segment using a zero-allocation window view
                final var frameSlice = videoSegment.asSlice(byteOffset, frameSize);

                // Draw centered bitmap directly from slice segment reference
                U8g2.u8g2_DrawBitmap(u8g2, (short) offsetX, (short) offsetY,
                        (short) (this.videoWidth / 8), (short) this.videoHeight, frameSlice);

                // Pre-check right before sending to protect against a late signal delivery
                if (!this.running || this.executionThread.isInterrupted()) {
                    break;
                }

                U8g2.u8g2_SendBuffer(u8g2);

                // Shift window by a structured frame size increment step
                byteOffset += frameSize;

                // Maintain target FPS pacing
                final var elapsedTime = System.nanoTime() - startTime;
                final var sleepTimeNs = frameDurationNs - elapsedTime;
                if (sleepTimeNs > 0) {
                    TimeUnit.NANOSECONDS.sleep(sleepTimeNs);
                }
            }
        } catch (final IOException e) {
            log.error("Resource error during setup: {}", e.getMessage());
        } catch (final InterruptedException e) {
            log.debug("Video execution thread context step interrupted clean.");
        } finally {
            this.running = false;
            log.info("Video playback complete.");
        }
    }

    /**
     * Loads the resource raw file contents directly into a native memory segment cache block.
     *
     * @param arena The targeted allocation allocation arena.
     * @return MemorySegment containing the unmanaged file byte payload.
     * @throws IOException If source file extraction stream is missing or broken.
     */
    private MemorySegment loadVideoFromResource(final Arena arena) throws IOException {
        try (final var is = Video.class.getResourceAsStream(this.fileName)) {
            if (is == null) {
                throw new IOException("Resource target context asset not found relative to package roots: " + this.fileName);
            }

            final var allBytes = is.readAllBytes();
            final var nativeSegment = arena.allocate(allBytes.length);

            // Blast the contents straight onto the native heap allocation block
            MemorySegment.copy(allBytes, 0, nativeSegment, ValueLayout.JAVA_BYTE, 0, allBytes.length);
            return nativeSegment;
        }
    }

    /**
     * Main entry point with automatic type conversion. Handles signal interception to prevent concurrent hardware teardown
     * execution paths.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        final var videoApp = new Video();

        // Register an application level hook that executes BEFORE Base can step on pointers
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            videoApp.running = false;
            if (videoApp.executionThread != null) {
                videoApp.executionThread.interrupt();
                try {
                    // Force the JVM shutdown sequence to wait for the hot loop to step out of native space cleanly
                    videoApp.executionThread.join(1000);
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }));

        System.exit(new CommandLine(videoApp).execute(args));
    }
}
