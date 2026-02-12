/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2.demo;

import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.u8g2.U8g2;
import picocli.CommandLine;

/**
 * Performance test for the FFM bridge specifically targeting buffer transmission.
 * <p>
 * This benchmark measures the time taken to execute the {@code u8g2_SendBuffer} function across a specified number of samples to
 * determine the I/O and transition overhead.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@CommandLine.Command(name = "SendBufferPerf", mixinStandardHelpOptions = true,
        description = "U8g2 FFM sendBuffer throughput benchmark")
public final class Perf extends Base {

    /**
     * Number of iterations to run the benchmark.
     */
    @CommandLine.Option(names = {"--samples"}, description = "Number of samples, ${DEFAULT-VALUE} by default.")
    private int samples = 1000;

    /**
     * Main entry point.
     *
     * @param args Command line arguments.
     */
    public static void main(final String[] args) {
        final var exitCode = new CommandLine(new Perf()).execute(args);
        System.exit(exitCode);
    }

    /**
     * Executes the sendBuffer benchmark.
     * <p>
     * This method bypasses higher-level logic to call the native {@code u8g2_SendBuffer_Java} method directly in a hot loop.
     *
     * @param u8g2 MemorySegment handle to the u8g2 struct.
     */
    @Override
    protected void run(final MemorySegment u8g2) {
        log.info("Timing {} sendBuffer calls using FFM...", samples);

        // Fill buffer once with a pattern so we aren't sending empty memory
        U8g2.u8g2_DrawFrame(u8g2, (short) 0, (short) 0, (short) getWidth(), (short) getHeight());

        final var start = Instant.now();

        for (var i = 0; i < samples; i++) {
            // Directly calling the jextract-generated native method
            U8g2.u8g2_SendBuffer(u8g2);
        }

        final var finish = Instant.now();
        final var timeElapsed = Duration.between(start, finish);
        final var millis = timeElapsed.toMillis();

        // Use double for precision in high-speed FFM calls
        final var avgMs = (double) millis / samples;

        log.info("Total time: {} ms", millis);
        log.info("Average time per sendBuffer: {} ms", String.format("%.4f", avgMs));

        if (avgMs > 0) {
            log.info("Theoretical Max FPS: {}", String.format("%.2f", 1000.0 / avgMs));
        }
    }
}
