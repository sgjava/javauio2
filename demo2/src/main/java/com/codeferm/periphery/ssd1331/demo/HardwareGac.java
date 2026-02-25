/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.ssd1331.demo;

import com.codeferm.periphery.device.Ssd1331;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Performance test for SSD1331 Hardware Acceleration (GAC).
 * <p>
 * Benchmarks Lines, Rectangles, and Window Copy operations. Demonstrates zero-CPU hardware scrolling.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "HardwareGac", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT")
public class HardwareGac extends Base {

    /**
     * Random number generator for coordinates and colors.
     */
    private final Random random = new Random();

    /**
     * Executes the hardware acceleration benchmark.
     *
     * @param oled SSD1331 driver instance.
     * @throws Exception On SPI errors.
     */
    public void demo(final Ssd1331 oled) throws Exception {
        final var w = getWidth();
        final var h = getHeight();
        final var durationMs = 5000L;

        log.info("Starting Hardware Acceleration Performance Test...");

        // --- Test 1: Random Lines ---
        var linesCount = 0;
        var start = System.currentTimeMillis();
        log.info("Benchmarking Lines...");
        while (System.currentTimeMillis() - start < durationMs) {
            oled.drawLine(
                    random.nextInt(w), random.nextInt(h),
                    random.nextInt(w), random.nextInt(h),
                    random.nextInt(64), random.nextInt(64), random.nextInt(64)
            );
            linesCount++;
        }
        log.info("Result: {} lines in 5s ({} lines/sec)", linesCount, linesCount / 5);

        TimeUnit.SECONDS.sleep(1);
        oled.clear();
        // --- Test 2: Random Filled Rectangles ---
        var rectCount = 0;
        start = System.currentTimeMillis();
        log.info("Benchmarking Filled Rectangles...");
        while (System.currentTimeMillis() - start < durationMs) {
            final var x1 = random.nextInt(w - 10);
            final var y1 = random.nextInt(h - 10);
            oled.drawRect(
                    x1, y1, x1 + random.nextInt(w - x1), y1 + random.nextInt(h - y1),
                    random.nextInt(64), random.nextInt(64), random.nextInt(64),
                    true
            );
            rectCount++;
        }
        log.info("Result: {} rects in 5s ({} rects/sec)", rectCount, rectCount / 5);
        TimeUnit.SECONDS.sleep(1);
        oled.clear();
        // --- Test 3: Hardware Copy Benchmark ---
        var copyCount = 0;
        log.info("Benchmarking Hardware Copy (20x20 block)...");
        oled.drawRect(0, 0, 20, 20, 63, 0, 0, true); // Source block
        start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < durationMs) {
            // Copy 20x20 area to random location
            oled.copy(0, 0, 20, 20, random.nextInt(w - 20), random.nextInt(h - 20));
            copyCount++;
        }
        log.info("Result: {} copies in 5s ({} copies/sec)", copyCount, copyCount / 5);
        TimeUnit.SECONDS.sleep(1);
        // --- Test 4: Hardware Scrolling ---
        log.info("Testing Hardware Scroll (Static header + Scrolling body)...");
        oled.clear();
        oled.drawRect(0, 0, w - 1, 10, 0, 63, 0, true); // Green header
        for (var i = 11; i < h; i += 4) {
            oled.drawLine(0, i, w - 1, i, 63, 63, 63);
        }
        log.info("Starting horizontal scroll: 1px right, rows 11-63");
        oled.setupScroll(1, 11, 53, 0, 0);
        TimeUnit.SECONDS.sleep(5);
        log.info("Starting diagonal scroll: 1px right, 1px down, rows 11-63");
        oled.setupScroll(1, 11, 53, 1, 0);
        TimeUnit.SECONDS.sleep(5);
        oled.stopScroll();
        log.info("Benchmark complete.");
    }

    /**
     * Execution logic for GAC demo.
     */
    @Override
    public Integer call() throws Exception {
        super.call();
        try {
            demo(getOled());
        } finally {
            done();
        }
        return 0;
    }

    /**
     * Main entry point using picocli.
     *
     * @param args Argument list.
     */
    public static void main(final String[] args) {
        System.exit(new CommandLine(new HardwareGac()).execute(args));
    }
}
