/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.device.Adxl345;
import com.codeferm.periphery.device.I2cBus;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * ADXL345 I2C accelerometer demo with clean terminal handling.
 * <p>
 * This application demonstrates the resource management pattern and utilizes a JVM shutdown hook to ensure terminal output is
 * cleaned up if the user interrupts the process via Ctrl+C.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Adxl345Demo", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Polls ADXL345 accelerometer data over I2C.")
public final class Adxl345Demo extends AbstractDemo {

    @Option(names = {"-d", "--device"}, description = "I2C device path, ${DEFAULT-VALUE} by default.")
    private String device = "/dev/i2c-1";

    @Option(names = {"-a", "--address"}, description = "I2C address (hex), ${DEFAULT-VALUE} by default.")
    private String addressHex = "53";

    @Option(names = {"-i", "--iterations"}, description = "Number of polls, ${DEFAULT-VALUE} by default.")
    private int iterations = 100;

    /**
     * Executes the demo logic with terminal cleanup.
     *
     * @return Exit code (0 for success, 1 for failure).
     */
    @Override
    public Integer call() {
        var exitCode = 0;
        final var address = Short.parseShort(addressHex, 16);

        // Add shutdown hook to fix terminal on Ctrl+C
        addTerminalHook();

        log.info("Starting ADXL345 Demo on {} (Address: 0x{})", device, addressHex);

        try (final var bus = new I2cBus(device, 256)) {
            try (final var accel = new Adxl345(bus, address)) {

                final var devId = accel.getDeviceId();
                if (devId != 0xE5) {
                    log.error("Invalid Device ID: 0x{}. Expected 0xE5.", Integer.toHexString(devId));
                    return 1;
                }

                log.info("ADXL345 detected. Enabling measurement...");
                accel.enable();

                for (int i = 0; i < iterations; i++) {
                    final var data = accel.read();

                    // Added spaces at the end to clear any lingering characters from previous wider lines
                    System.out.printf("\rIteration %d/%d | X: %6.2f | Y: %6.2f | Z: %6.2f m/s²    ",
                            i + 1, iterations, data.get("x"), data.get("y"), data.get("z"));

                    Thread.sleep(100);
                }
                // Natural exit is now handled by the hook or this println
                System.out.println();

            }
            log.info("Hardware standby command sent.");

        } catch (final InterruptedException e) {
            // Interrupted via Ctrl+C, handle gracefully
            Thread.currentThread().interrupt();
        } catch (final Exception e) {
            log.error("Demo failed: {}", e.getMessage());
            exitCode = 1;
        }

        return exitCode;
    }

    public static void main(final String... args) {
        System.exit(new CommandLine(new Adxl345Demo()).execute(args));
    }
}
