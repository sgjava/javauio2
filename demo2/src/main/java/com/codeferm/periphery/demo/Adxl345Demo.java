package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.Adxl345;
import java.lang.foreign.Arena;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.i2c_handle;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * ADXL345 demo using high-level FFM device class.
 * <p>
 * This demo includes a shutdown hook to ensure the terminal remains clean after a SIGINT (Ctrl+C).
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0-SNAPSHOT
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Adxl345Demo", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Read ADXL345 data using FFM Adxl345 device class.")
public class Adxl345Demo implements Callable<Integer> {

    static {
        NativeLoader.load();
    }

    /**
     * I2C device path (e.g., /dev/i2c-1).
     */
    @Option(names = {"-d", "--device"}, description = "I2C device, ${DEFAULT-VALUE} by default.")
    private String device = "/dev/i2c-1";

    /**
     * I2C slave address of the ADXL345.
     */
    @Option(names = {"-a", "--address"}, description = "Address, ${DEFAULT-VALUE} by default.")
    private short address = 0x53;

    /**
     * Execution logic for the ADXL345 sampler.
     *
     * @return Exit code (0 for success, 1 for failure).
     */
    @Override
    public Integer call() {
        var exitCode = 0;

        // Add shutdown hook to move to next line on Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println(); // Next line after the \r output
            log.info("Closing demo gracefully...");
        }));

        try (final var arena = Arena.ofConfined()) {
            final var handle = arena.allocate(i2c_handle.layout());
            final var path = arena.allocateFrom(device);

            log.info("Opening I2C device: {}", device);
            if (Periphery.i2c_open(handle, path) < 0) {
                log.error("Failed to open I2C device: {}", device);
                return 1;
            }

            try (final var adxl = new Adxl345(arena, handle, address)) {
                final var deviceId = adxl.getDeviceId();

                if (deviceId == (short) 0xe5) {
                    log.info("ADXL345 detected at 0x{}. Initializing...", Integer.toHexString(address));
                    adxl.enable();

                    log.info("Starting sample collection (Ctrl+C to stop)...");
                    while (!Thread.currentThread().isInterrupted()) {
                        final var data = adxl.read();

                        // Use %+5.2f to maintain fixed width and trailing spaces to clear artifacts
                        System.out.printf("\rSample - x: %+5.2f, y: %+5.2f, z: %+5.2f   ",
                                data.get("x"), data.get("y"), data.get("z"));

                        TimeUnit.MILLISECONDS.sleep(100);
                    }
                } else {
                    log.error("Device ID mismatch. Expected 0xE5, got 0x{}", Integer.toHexString(deviceId));
                    exitCode = 1;
                }
            } catch (final InterruptedException e) {
                // Restore interrupted status and allow graceful exit
                Thread.currentThread().interrupt();
            } catch (final Exception e) {
                log.error("Device error: {}", e.getMessage(), e);
                exitCode = 1;
            }
        } catch (final Exception e) {
            log.error("Native or Runtime error: {}", e.getMessage());
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
