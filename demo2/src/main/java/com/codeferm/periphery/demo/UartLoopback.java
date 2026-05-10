/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.Uart;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * UART loopback demo using the FFM-based Uart device class.
 * <p>
 * This demo requires a physical jumper connecting RX to TX on the specified serial device. It demonstrates thread-safe writing and
 * reading using the picocli framework for command-line configuration.
 * </p>
 * <p>
 * This demo explicitly manages the native buffer size and utilizes the Foreign Function & Memory API via the high-level wrapper.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "UartLoopback", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "UART loopback test (requires RX to TX jumper).")
public class UartLoopback implements Callable<Integer> {

    static {
        // Load native periphery library for FFM usage
        NativeLoader.load();
    }

    /**
     * Serial device path option.
     */
    @Option(names = {"-d", "--device"}, description = "Serial device, ${DEFAULT-VALUE} by default.",
            defaultValue = "/dev/ttyS2")
    private String device;

    /**
     * Baud rate option.
     */
    @Option(names = {"-b", "--baud"}, description = "Baud rate, ${DEFAULT-VALUE} by default.",
            defaultValue = "115200")
    private int baud;

    /**
     * Native buffer size option. No hidden defaults in the device class.
     */
    @Option(names = {"-s", "--size"}, description = "Native buffer size, ${DEFAULT-VALUE} by default.",
            defaultValue = "1024")
    private int bufferSize;

    /**
     * Executes the loopback test logic.
     * <p>
     * Initializes the UART device with an explicit buffer size, sends a test string, and attempts to read it back within a 1-second
     * timeout.
     * </p>
     *
     * @return Exit code (0 for success, 1 for failure or data mismatch).
     */
    @Override
    public Integer call() {
        var exitCode = 0;
        final var testStr = "Hello Periphery FFM!";

        log.info("Starting UART Loopback on {} at {} baud", device, baud);

        // Uart class requires explicit bufferSize (No Magic Numbers)
        try (final var uart = new Uart(device, baud, bufferSize)) {
            final var tx = testStr.getBytes(StandardCharsets.UTF_8);
            final var rx = new byte[tx.length];

            log.info("Sending: '{}'", testStr);

            // Thread-safe write using pre-allocated native memory internally
            uart.write(tx);

            // Wait up to 1 second for loopback data to arrive
            final var bytesRead = uart.read(rx, 1000);

            if (bytesRead > 0) {
                final var result = new String(rx, 0, bytesRead, StandardCharsets.UTF_8);
                log.info("Received: '{}'", result);

                if (testStr.equals(result)) {
                    log.info("Loopback successful!");
                } else {
                    log.warn("Data corruption detected (mismatch).");
                    exitCode = 1;
                }
            } else {
                log.error("No data received. Is the jumper connected between RX (Pin 10) and TX (Pin 8)?");
                exitCode = 1;
            }
        } catch (final Exception e) {
            log.error("UART error during demo: {}", e.getMessage());
            exitCode = 1;
        }
        return exitCode;
    }

    /**
     * Main entry point for the UART loopback application.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new UartLoopback()).execute(args));
    }
}
