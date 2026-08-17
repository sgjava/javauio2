/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.device.SpiBus;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * SPI loopback demo using FFM-based SpiBus.
 * <p>
 * This demo verifies the SPI controller and wiring. To pass, a physical jumper must be placed between the MISO and MOSI pins on the
 * board.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "SpiLoopback", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "SPI loopback test (requires MISO to MOSI jumper).")
public class SpiLoopback extends AbstractDemo {

    /**
     * SPI device path.
     */
    @Option(names = {"-d", "--device"}, description = "SPI device, ${DEFAULT-VALUE} by default.")
    private String device = "/dev/spidev0.0";

    /**
     * SPI mode.
     */
    @Option(names = {"-m", "--mode"}, description = "SPI mode, ${DEFAULT-VALUE} by default.")
    private int mode = 0;

    /**
     * Max clock speed in Hz.
     */
    @Option(names = {"-s", "--speed"}, description = "Max speed in Hz, ${DEFAULT-VALUE} by default.")
    private int speed = 500000;

    /**
     * Execution logic for the SPI loopback demo.
     * <p>
     * Initializes the SpiBus with a 1K native buffer for this simple test.
     * </p>
     *
     * @return Exit code (0 for success, 1 for failure).
     * @throws Exception On hardware or execution error.
     */
    @Override
    public Integer call() throws Exception {
        var exitCode = 0;
        addTerminalHook();
        // Simple loopback doesn't need much, using 1K buffer
        final var bufferSize = 1024;

        log.info("Starting SPI loopback on {}...", device);

        // SpiBus manages native memory and handles via internal Arena
        try (final var spiBus = new SpiBus(device, mode, speed, bufferSize)) {
            final var tx = new byte[]{0x01, 0x02, 0x03, 0x04, (byte) 0xff};
            final var rx = new byte[tx.length];

            log.atDebug().log("Bus status: {}", spiBus.toString());

            // Perform hardware transfer (Full-duplex)
            final var ret = spiBus.transfer(tx, rx, tx.length);

            if (ret == 0) {
                log.info("TX: {}", Arrays.toString(tx));
                log.info("RX: {}", Arrays.toString(rx));

                if (Arrays.equals(tx, rx)) {
                    log.info("Loopback check passed!");
                } else {
                    log.warn("Loopback check failed! Verify MISO to MOSI jumper is secure.");
                    exitCode = 1;
                }
            } else {
                log.error("SPI transfer failed with return code: {}", ret);
                exitCode = 1;
            }
        } catch (final Exception e) {
            log.error("SPI demo failed: {}", e.getMessage());
            exitCode = 1;
        }
        return exitCode;
    }

    /**
     * Main entry point for the SPI loopback application.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new SpiLoopback()).execute(args));
    }
}
