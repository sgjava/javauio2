/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Display performance demo simulating real-world partial updates (dirty writes) without clearing the screen between frames.
 * <p>
 * Evaluates the throughput of updating localized screen regions (25%, 50%, 75%, and 100% of display area) repeatedly to mirror
 * actual sprite or widget rendering performance.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.4.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Perf", mixinStandardHelpOptions = true, version = "1.4.0-SNAPSHOT",
        description = "Display performance demo with real-world partial dirty writes")
public class Perf extends Base {

    /**
     * Number of frame transfer samples to measure per test phase.
     */
    @Option(names = {"--samples"}, description = "Number of frame samples per step, ${DEFAULT-VALUE} by default.")
    private int samples = 100;

    /**
     * Starting SPI frequency in MHz.
     */
    @Option(names = {"--start-mhz"}, description = "Start SPI frequency in MHz, ${DEFAULT-VALUE} by default.")
    private int startMhz = 8;

    /**
     * Ending SPI frequency in MHz.
     */
    @Option(names = {"--end-mhz"}, description = "End SPI frequency in MHz, ${DEFAULT-VALUE} by default.")
    private int endMhz = 64;

    /**
     * Frequency step increment in MHz.
     */
    @Option(names = {"--step-mhz"}, description = "SPI frequency step in MHz, ${DEFAULT-VALUE} by default.")
    private int stepMhz = 8;

    /**
     * Minimum percentage gain required to continue scaling frequency.
     */
    @Option(names = {"--min-gain-pct"}, description
            = "Minimum FPS gain percentage to continue scaling, ${DEFAULT-VALUE} by default.")
    private double minGainPct = 2.0;

    /**
     * Executes the optimal frequency sweep followed by real-world partial dirty write benchmarks.
     *
     * @return Exit code.
     * @throws Exception Possible hardware or memory access exception.
     */
    @Override
    public final Integer call() throws Exception {
        log.info("Starting optimal SPI frequency sweep from {} MHz to {} MHz", startMhz, endMhz);

        var bestMhz = startMhz;
        var bestFps = 0.0;
        var previousFps = 0.0;

        // Phase 1: Find optimal frequency
        for (var mhz = startMhz; mhz <= endMhz; mhz += stepMhz) {
            System.setProperty("display.speed", String.valueOf(mhz * 1_000_000L));
            super.call();

            final var w = getWidth();
            final var h = getHeight();
            final var display = getDisplay();
            final var g2d = getG2d();
            final var testImage = getImage();

            g2d.setColor(Color.BLUE);
            g2d.fillRect(0, 0, w, h);
            g2d.setColor(Color.WHITE);
            g2d.drawRect(5, 5, w - 11, h - 11);

            display.drawImage(testImage, 0, 0, w, h);

            final var start = Instant.now();
            for (var i = 0; i < samples; i++) {
                display.drawImage(testImage, 0, 0, w, h);
            }
            final var finish = Instant.now();

            final var timeElapsed = Duration.between(start, finish);
            final var totalSeconds = timeElapsed.toNanos() / 1_000_000_000.0;
            final var fps = samples / totalSeconds;

            log.info("SPI Speed: {} MHz -> Throughput: {} FPS", mhz, String.format("%.2f", fps));

            if (fps > bestFps) {
                bestFps = fps;
                bestMhz = mhz;
            }

            if (mhz > startMhz) {
                final var gainPct = ((fps - previousFps) / previousFps) * 100.0;
                if (gainPct < minGainPct) {
                    log.info("Diminishing returns reached: gain of {}% is below threshold ({}%). Locking optimal speed.", String.
                            format("%.2f", gainPct), minGainPct);
                    done();
                    break;
                }
            }

            previousFps = fps;
            done();
        }

        log.info("==========================================");
        log.info("Highest efficient SPI frequency locked at: {} MHz (~{} FPS)", bestMhz, String.format("%.2f", bestFps));
        log.info("==========================================");

        // Phase 2: Real-world partial dirty writes without screen clearing between frames
        System.setProperty("display.speed", String.valueOf(bestMhz * 1_000_000L));
        super.call();

        final var screenWidth = getWidth();
        final var screenHeight = getHeight();
        final var display = getDisplay();
        final var g2d = getG2d();
        final var fullImage = getImage();

        // Initial full background clear once
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, screenWidth, screenHeight);
        display.drawImage(fullImage, 0, 0, screenWidth, screenHeight);

        final int[] percentages = {25, 50, 75, 100};

        log.info("Starting real-world partial dirty write benchmarks using optimal speed {} MHz", bestMhz);

        for (final var pct : percentages) {
            final var targetWidth = (int) (screenWidth * Math.sqrt(pct / 100.0));
            final var targetHeight = (int) (screenHeight * Math.sqrt(pct / 100.0));

            final var x = (screenWidth - targetWidth) / 2;
            final var y = (screenHeight - targetHeight) / 2;

            // Render a dynamic color block into the image buffer for this region
            g2d.setColor(pct == 100 ? Color.BLUE : Color.RED);
            g2d.fillRect(x, y, targetWidth, targetHeight);
            g2d.setColor(Color.YELLOW);
            g2d.drawRect(x, y, targetWidth, targetHeight);

            // Benchmark loop without intermediate background clears
            final var start = Instant.now();
            for (var i = 0; i < samples; i++) {
                display.drawImage(fullImage, x, y, targetWidth, targetHeight);
            }
            final var finish = Instant.now();

            final var timeElapsed = Duration.between(start, finish);
            final var totalSeconds = timeElapsed.toNanos() / 1_000_000_000.0;
            final var fps = samples / totalSeconds;

            log.info("Dirty Write [{}% area - {}x{} at ({},{})]: Throughput: {} FPS (Time: {}s)",
                    pct, targetWidth, targetHeight, x, y, String.format("%.2f", fps), String.format("%.4f", totalSeconds));
        }

        done();
        return 0;
    }

    /**
     * Main entry point using picocli.
     *
     * @param args Argument list.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new Perf()).execute(args));
    }
}
