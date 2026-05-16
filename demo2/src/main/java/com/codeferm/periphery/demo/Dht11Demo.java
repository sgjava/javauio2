/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.device.Dht11;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * DHT11 IIO sensor demo.
 * <p>
 * Updated to reflect Fahrenheit as the default temperature unit.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.1.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Dht11Demo", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT")
public final class Dht11Demo extends AbstractDemo {

    @Option(names = {"-i", "--iio"}, description = "IIO device name.", defaultValue = "iio:device0")
    private String iioDevice;

    @Option(names = {"-n", "--iterations"}, description = "Number of reads.", defaultValue = "10")
    private int iterations;

    /**
     * Executes the DHT11 sensor polling logic.
     *
     * @return Exit code (0 for success, 1 for failure).
     */
    @Override
    public Integer call() {
        var exitCode = 0;

        // Ensure clean terminal output on interrupt via base class
        addTerminalHook();

        log.info("Starting DHT11 IIO Demo on {}", iioDevice);

        final var sensor = new Dht11(iioDevice);

        try {
            for (var i = 0; i < iterations; i++) {
                if (sensor.read()) {
                    // Updated log format for F default
                    log.info("Read successful: Temp: {}°F, Humidity: {}%",
                            String.format("%.1f", sensor.getTemperature()),
                            sensor.getHumidity());
                } else {
                    log.warn("Read failed: Check kernel IIO availability.");
                }

                if (i < iterations - 1) {
                    TimeUnit.SECONDS.sleep(2);
                }
            }
        } catch (final InterruptedException e) {
            log.error("Demo interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
            exitCode = 1;
        } catch (final Exception e) {
            log.error("Demo failed: {}", e.getMessage());
            exitCode = 1;
        }
        return exitCode;
    }

    /**
     * Main entry point using picocli.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new Dht11Demo()).execute(args));
    }
}
