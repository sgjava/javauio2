/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.device.Uart;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * High-performance UART stress test for Linux-based SoC hardware.
 * <p>
 * This demo performs a full-duplex stress test using a loopback jumper. It measures effective throughput (bps) and validates data
 * integrity across a specified number of iterations.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "UartStressTest", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "UART throughput and integrity stress test.")
public class UartStress extends AbstractDemo {

    /**
     * Serial device path.
     */
    @Option(names = {"-d", "--device"}, description = "Serial device path.", defaultValue = "/dev/ttyS2")
    private String device;

    /**
     * Baud rate (e.g. 115200, 921600, 1500000).
     */
    @Option(names = {"-b", "--baud"}, description = "Baud rate.", defaultValue = "115200")
    private int baud;

    /**
     * Number of test iterations.
     */
    @Option(names = {"-i", "--iterations"}, description = "Number of test iterations.", defaultValue = "100")
    private int iterations;

    /**
     * Chunk size per write (bytes).
     */
    @Option(names = {"-s", "--size"}, description = "Chunk size per write (bytes).", defaultValue = "1024")
    private int chunkSize;

    /**
     * Executes the stress test loop.
     *
     * @return 0 for success, 1 if data corruption or timeout occurs.
     * @throws Exception On hardware or execution error.
     */
    @Override
    public Integer call() throws Exception {
        var exitCode = 0;
        addTerminalHook();

        final var tx = new byte[chunkSize];
        final var rx = new byte[chunkSize];

        // Fill TX buffer with a recognizable pattern (0-255)
        for (int i = 0; i < chunkSize; i++) {
            tx[i] = (byte) (i & 0xFF);
        }

        log.info("Starting Stress Test: {} @ {} baud", device, baud);
        log.info("Configuration: {} iterations, {} byte chunks", iterations, chunkSize);

        try (final var uart = new Uart(device, baud, chunkSize)) {
            uart.flush();
            final var startTime = System.nanoTime();
            var totalBytes = 0L;

            for (var i = 0; i < iterations; i++) {
                final var written = uart.write(tx);
                if (written != chunkSize) {
                    log.error("Short write at iteration {}: {} bytes", i, written);
                    return 1;
                }

                // Wait for chunk with a 2-second timeout
                final var readCount = uart.read(rx, 2000);

                if (readCount != chunkSize) {
                    log.error("Read failure at iteration {}: Expected {}, Got {}", i, chunkSize, readCount);
                    return 1;
                }

                if (!Arrays.equals(tx, rx)) {
                    log.error("Data corruption at iteration {}!", i);
                    return 1;
                }

                totalBytes += readCount;

                // Real-time progress with \r to keep terminal clean
                System.out.printf("\rProgress: %d/%d | Total: %d bytes | Throughput: %.2f KB/s",
                        i + 1, iterations, totalBytes,
                        (totalBytes / 1024.0) / ((System.nanoTime() - startTime) / 1_000_000_000.0));
                System.out.flush();
            }

            final var totalTime = (System.nanoTime() - startTime) / 1_000_000_000.0;
            System.out.println(); // Final newline

            log.info("Test Complete!");
            log.info("Duration: {} seconds", "%.2f".formatted(totalTime));

            // Throughput in bps and Mbps
            final var bps = (totalBytes * 8.0) / totalTime;
            log.info("Final Avg Throughput: {} bps ({})",
                    "%.2f".formatted(bps),
                    "%.2f Mbps".formatted(bps / 1_000_000.0));

        } catch (final Exception e) {
            log.error("Stress test failed: {}", e.getMessage());
            exitCode = 1;
        }

        return exitCode;
    }

    /**
     * Main entry point for the UART stress test.
     *
     * * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new UartStress()).execute(args));
    }
}
