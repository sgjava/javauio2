/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.GpioLed;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Blink LED using high-level FFM device abstraction.
 * <p>
 * This demo utilizes the GpioLed class which wraps jextract-generated bindings. Ensure your hardware is rigged with a
 * current-limiting resistor (e.g., 220Ω) between the GPIO pin and the LED anode.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "LedBlink", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Blink LED using high-level FFM GpioLed device.")
public class LedBlink implements Callable<Integer> {

    static {
        // Load native library for the underlying GpioLed FFM calls
        NativeLoader.load();
    }

    /**
     * GPIO device path.
     */
    @Option(names = {"-d", "--device"}, description = "GPIO device, ${DEFAULT-VALUE} by default.",
            defaultValue = "/dev/gpiochip0")
    private String device;

    /**
     * GPIO line number.
     */
    @Option(names = {"-l", "--line"}, description = "GPIO line, ${DEFAULT-VALUE} by default.",
            defaultValue = "77")
    private int line;

    /**
     * Executes the blink sequence using the GpioLed device.
     *
     * @return Exit code (0 for success, 1 for failure).
     */
    @Override
    public Integer call() {
        var exitCode = 0;

        log.info("Starting LED Blink: {} Line {}", device, line);

        // GpioLed handles FFM Arena and gpio_handle allocation internally
        try (final var led = new GpioLed(device, line)) {
            for (var i = 0; i < 10; i++) {
                log.atDebug().log("Cycle {}: LED ON", i);
                led.on();
                TimeUnit.SECONDS.sleep(1);

                log.atDebug().log("Cycle {}: LED OFF", i);
                led.off();
                TimeUnit.SECONDS.sleep(1);
            }
            log.info("Blink sequence complete.");
        } catch (final InterruptedException e) {
            log.error("Blink sequence interrupted");
            Thread.currentThread().interrupt();
            exitCode = 1;
        } catch (final Exception e) {
            log.error("Failed to operate LED: {}", e.getMessage());
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
        final var cmd = new CommandLine(new LedBlink());
        System.exit(cmd.execute(args));
    }
}
