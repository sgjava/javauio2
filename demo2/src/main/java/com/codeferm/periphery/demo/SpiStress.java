/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.SpiBus;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.zip.CRC32;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * SPI stress test with automatic kernel buffer probing.
 * <p>
 * This application probes the Linux kernel's maximum SPI buffer size by iteratively increasing the transfer length until an error
 * occurs. Once the hardware limit is identified, it executes a multi-megabyte stress test using CRC32 checksums to verify data
 * integrity over a physical loopback jumper.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0-SNAPSHOT
 * @since 1.0.0
 */
@Slf4j
@Command(name = "SpiStressTest", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Probe SPI buffer size and perform CRC32 stress test.")
public class SpiStress implements Callable<Integer> {

    static {
        // Ensure native periphery library is loaded before bus initialization
        NativeLoader.load();
    }

    /**
     * SPI device path.
     */
    @Option(names = {"-d", "--device"}, description = "SPI device, ${DEFAULT-VALUE} by default.")
    private String device = "/dev/spidev0.0";

    /**
     * SPI clock frequency in Hz.
     */
    @Option(names = {"-s", "--speed"}, description = "Max speed in Hz, ${DEFAULT-VALUE} by default.")
    private int speed = 10000000;

    /**
     * Total amount of data to transfer for the stress test.
     */
    @Option(names = {"-z", "--size"}, description = "Total megabytes to transfer, ${DEFAULT-VALUE} by default.")
    private int totalMegs = 5;

    /**
     * Maximum native buffer ceiling for probing (128KB).
     */
    private static final int PROBE_CEILING = 128 * 1024;

    /**
     * Probes the maximum buffer size supported by the Linux spidev driver.
     * <p>
     * Iterates from 1KB up to the {@link #PROBE_CEILING}, returning the last successful transfer size.
     * </p>
     *
     * @param spiBus The initialized SPI bus instance.
     * @return The maximum successful transfer size in bytes.
     */
    private int probeMaxBufferSize(final SpiBus spiBus) {
        log.info("Probing kernel SPI buffer size...");
        var currentSize = 1024;
        var lastValidSize = 1024;

        // Incrementally push the buffer size until failure or ceiling
        while (currentSize <= PROBE_CEILING) {
            final var tx = new byte[currentSize];
            final var rx = new byte[currentSize];

            if (spiBus.transfer(tx, rx, currentSize) < 0) {
                log.info("Probe limit reached at {} bytes. Max kernel buffer: {} bytes.",
                        currentSize, lastValidSize);
                return lastValidSize;
            }

            lastValidSize = currentSize;
            currentSize += 1024; // Increment by 1K
        }
        return lastValidSize;
    }

    /**
     * Executes the SPI stress test and validates data using CRC32.
     *
     * @return Exit code (0 for success, 1 for failure).
     */
    @Override
    public Integer call() {
        var exitCode = 0;
        final var random = new Random();
        final var txCrc = new CRC32();
        final var rxCrc = new CRC32();

        // Initialize SpiBus with the probe ceiling to ensure native memory is available
        try (final var spiBus = new SpiBus(device, 0, speed, PROBE_CEILING)) {
            // 1. Discover hardware/kernel limit
            final int maxBuf = probeMaxBufferSize(spiBus);

            // 2. Prepare for stress test
            final var txChunk = new byte[maxBuf];
            final var rxChunk = new byte[maxBuf];
            final long totalBytes = (long) totalMegs * 1024 * 1024;
            final long iterations = totalBytes / maxBuf;

            log.info("Starting {}MB stress test using {} byte chunks...", totalMegs, maxBuf);
            final long startTime = System.currentTimeMillis();

            for (long i = 0; i < iterations; i++) {
                random.nextBytes(txChunk);
                txCrc.update(txChunk);

                // Perform the native FFM transfer
                if (spiBus.transfer(txChunk, rxChunk, maxBuf) < 0) {
                    log.error("Transfer failed at iteration {}", i);
                    return 1;
                }

                rxCrc.update(rxChunk);

                if (i % 20 == 0) {
                    System.out.printf("\rProgress: %.1f%% (%d/%d chunks)",
                            (i * 100.0 / iterations), i, iterations);
                }
            }

            final long endTime = System.currentTimeMillis();
            final double seconds = (endTime - startTime) / 1000.0;

            System.out.println("\nTransfer Complete.");
            log.info("Throughput: {} MB/s", "%.2f".formatted(totalMegs / seconds));
            log.info("TX CRC32: {}", Long.toHexString(txCrc.getValue()).toUpperCase());
            log.info("RX CRC32: {}", Long.toHexString(rxCrc.getValue()).toUpperCase());

            if (txCrc.getValue() == rxCrc.getValue()) {
                log.info("SUCCESS: Data integrity verified via CRC32.");
            } else {
                log.error("FAILURE: CRC mismatch! Check signal integrity or jumper wires.");
                exitCode = 1;
            }

        } catch (final Exception e) {
            log.error("Application error: {}", e.getMessage());
            exitCode = 1;
        }
        return exitCode;
    }

    /**
     * Entry point for the SPI Stress Test.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new SpiStress()).execute(args));
    }
}
