/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.ssd1331.demo;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * SSD1331 drawImage performance demo using FFM.
 * <p>
 * This class measures the raw transfer speed of a static image to the SSD1331 display. By utilizing the Foreign Function & Memory
 * API (FFM), it benchmarks the efficiency of memory segment transfers compared to traditional JNI-based approaches.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.1.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Perf", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "SSD1331 drawImage performance demo using FFM")
public class Perf extends Base {

    /**
     * Number of frame transfer samples to measure.
     */
    @Option(names = {"--samples"}, description = "Number of frame samples, ${DEFAULT-VALUE} by default.")
    private int samples = 1000;

    /**
     * Calculate drawImage FPS using FFM memory segments.
     * <p>
     * Initializes the hardware, prepares a static test frame in the cached buffer, and performs a tight loop of transfers to
     * calculate throughput.
     * </p>
     *
     * @return Exit code.
     * @throws Exception Possible hardware or memory access exception.
     */
    @Override
    public Integer call() throws Exception {
        // super.call() initializes hardware and caches width/height/graphics in Base
        super.call();

        final var w = getWidth();
        final var h = getHeight();
        final var oled = getOled();
        final var g2d = getG2d();
        final var testImage = getImage();

        // Prepare a static image in the pre-allocated buffer for raw transfer testing
        g2d.setColor(Color.BLUE);
        g2d.fillRect(0, 0, w, h);
        // Add a small visual indicator for the test
        g2d.setColor(Color.WHITE);
        g2d.drawRect(5, 5, w - 11, h - 11);

        log.info("Benchmarking {} FFM drawImage transfers at {} Hz SPI speed", samples, getSpeed());

        // Start timing the loop
        final var start = Instant.now();
        for (var i = 0; i < samples; i++) {
            // This now uses the FFM MemorySegment pipeline
            oled.drawImage(testImage);
        }
        final var finish = Instant.now();

        // Calculate performance metrics
        final var timeElapsed = Duration.between(start, finish);
        final var totalSeconds = timeElapsed.toNanos() / 1_000_000_000.0;
        final var fps = samples / totalSeconds;

        log.info("Transfer results for {}x{} resolution:", w, h);
        log.info("Total time: {} seconds", String.format("%.4f", totalSeconds));
        log.info("FFM throughput: {} FPS", String.format("%.2f", fps));

        // Finalize hardware state
        done();

        return 0;
    }

    /**
     * Main entry point using picocli.
     *
     * @param args Argument list.
     */
    public static void main(final String[] args) {
        System.exit(new CommandLine(new Perf()).execute(args));
    }
}
