package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.Adxl345;
import com.codeferm.periphery.device.I2cBus;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * ADXL345 demo using high-level I2cBus and Adxl345 device classes.
 * <p>
 * This demo utilizes the refactored "Bus + Device" architecture. The I2cBus manages the native FFM resources and thread safety,
 * while the Adxl345 handles the sensor-specific logic.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0-SNAPSHOT
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Adxl345Demo", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Read ADXL345 data using FFM I2cBus and Adxl345 classes.")
public class Adxl345Demo implements Callable<Integer> {

    static {
        NativeLoader.load();
    }

    /**
     * I2C device path.
     */
    @Option(names = {"-d", "--device"}, description = "I2C device, ${DEFAULT-VALUE} by default.")
    private String device = "/dev/i2c-1";

    /**
     * I2C address of ADXL345.
     */
    @Option(names = {"-a", "--address"}, description = "Address, ${DEFAULT-VALUE} by default.")
    private short address = 0x53;

    /**
     * Execution logic for the demo.
     *
     * @return Exit code (0 for success, 1 for failure).
     */
    @Override
    public Integer call() {
        var exitCode = 0;

        // Add shutdown hook to handle Ctrl+C cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println(); // Break the \r line
            log.info("Shutdown signal received. Exiting...");
        }));

        // 1. Initialize the shared bus (Owns the native handle and Arena)
        try (final var bus = new I2cBus(device)) {

            // 2. Initialize the device (Uses the bus)
            try (final var adxl = new Adxl345(bus, address)) {
                final var deviceId = adxl.getDeviceId();

                if (deviceId == (short) 0xe5) {
                    log.info("ADXL345 detected at 0x{}. Initializing...", Integer.toHexString(address));
                    adxl.enable();

                    log.info("Starting sample collection (Ctrl+C to stop)...");
                    while (!Thread.currentThread().isInterrupted()) {
                        final var data = adxl.read();

                        System.out.printf("\rSample - x: %+5.2f, y: %+5.2f, z: %+5.2f   ",
                                data.get("x"), data.get("y"), data.get("z"));

                        TimeUnit.MILLISECONDS.sleep(100);
                    }
                } else {
                    log.error("Device ID mismatch. Expected 0xE5, got 0x{}", Integer.toHexString(deviceId));
                    exitCode = 1;
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final Exception e) {
                log.error("Device error: {}", e.getMessage());
                exitCode = 1;
            }
        } catch (final Exception e) {
            log.error("Bus error: {}", e.getMessage());
            exitCode = 1;
        }

        return exitCode;
    }

    /**
     * Main entry point.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new Adxl345Demo()).execute(args));
    }
}
