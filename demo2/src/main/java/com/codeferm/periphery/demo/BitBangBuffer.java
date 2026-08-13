/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.PeripheryHelper;
import com.codeferm.periphery.device.GpioOut;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * High-speed buffer bit-bang performance and LED flash demo using c-periphery GPIO and GpioOut.
 * <p>
 * This demo utilizes a large data buffer (5MB) alternating between 0x00 and 0xFF to measure throughput (bits per second) while
 * bit-banging natively through the FFM layer.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "BitBangBuffer",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "Performs high-speed native buffer bit-banging performance test via c-periphery GPIO.")
public final class BitBangBuffer extends AbstractDemo {

    /**
     * GPIO device path.
     */
    @Option(names = {"-d", "--device"}, description = "GPIO device", defaultValue = "/dev/gpiochip0")
    private String device;

    /**
     * GPIO line number.
     */
    @Option(names = {"-l", "--line"}, description = "GPIO line", defaultValue = "203")
    private int line;

    /**
     * Buffer size in bytes (default 5MB).
     */
    @Option(names = {"-s", "--size"}, description = "Buffer size in bytes", defaultValue = "1000000")
    private int bufferSize;

    /**
     * Number of iterations to run the benchmark.
     */
    @Option(names = {"-i", "--iterations"}, description = "Number of iterations", defaultValue = "5")
    private int iterations;

    /**
     * Orchestrates the high-speed bit-bang performance test.
     *
     * @return Exit code (0 for success, 1 for failure).
     */
    @Override
    public Integer call() {
        // Clean up terminal on interrupt
        addTerminalHook();

        log.info("Starting BitBangBuffer performance test on {} line {} [Buffer: {} bytes, Iterations: {}]",
                device, line, bufferSize, iterations);

        // Single Ownership. GpioOut manages the underlying handle lifecycle.
        try (final var out = new GpioOut(device, line)) {
            // Allocate 5MB buffer and populate pattern (alternating blocks of 0x00 and 0xFF)
            final byte[] buffer = new byte[bufferSize];
            for (var i = 0; i < buffer.length; i++) {
                buffer[i] = (i / 1024 % 2 == 0) ? (byte) 0x00 : (byte) 0xFF;
            }

            log.info("Buffer allocated and populated. Starting bit-bang benchmark...");

            // Access protected getHandle() via reflection to satisfy encapsulation
            final Method method = out.getClass().getSuperclass().getDeclaredMethod("getHandle");
            method.setAccessible(true);
            final var handle = (java.lang.foreign.MemorySegment) method.invoke(out);

            var totalBits = 0L;
            var totalTimeNs = 0L;

            for (var iter = 1; iter <= iterations; iter++) {
                final var startTime = System.nanoTime();

                PeripheryHelper.bitbangBuffer(handle, buffer);

                final var endTime = System.nanoTime();
                final var durationNs = endTime - startTime;

                final var bits = (long) buffer.length * 8L;
                totalBits += bits;
                totalTimeNs += durationNs;

                final var durationSec = durationNs / 1_000_000_000.0;
                final var bps = bits / durationSec;
                final var mbps = bps / 1_000_000.0;

                log.info("Iteration {}: Transferred {} bits in {} ms ({}, {} Mbps)",
                        iter, bits, durationNs / 1_000_000,
                        String.format("%,.2f bps", bps),
                        String.format("%,.2f", mbps));

                // Small delay between iterations if needed
                if (iter < iterations) {
                    TimeUnit.MILLISECONDS.sleep(200);
                }
            }

            final var totalDurationSec = totalTimeNs / 1_000_000_000.0;
            final var avgBps = totalBits / totalDurationSec;
            final var avgMbps = avgBps / 1_000_000.0;

            log.info("=== Performance Summary ===");
            log.info("Total Bits: {}", totalBits);
            log.info("Total Time: {} ms", totalTimeNs / 1_000_000);
            log.info("Average Throughput: {}, {} Mbps",
                    String.format("%,.2f bps", avgBps),
                    String.format("%,.2f", avgMbps));
            log.info("BitBangBuffer completed successfully.");

            return 0;
        } catch (final Exception e) {
            log.error("BitBangBuffer failure: {}", e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Main entry point for the picocli command.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new BitBangBuffer()).execute(args));
    }
}
