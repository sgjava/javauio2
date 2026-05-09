/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.GpioOut;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Demo for GpioOut device.
 * <p>
 * This demo works for any digital output device like an Active Buzzer or LED. It toggles the state based on the provided line and
 * delay.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "GpioOutDemo",
        mixinStandardHelpOptions = true,
        version = "1.0.0",
        description = "Toggles a GPIO output device (Buzzer/LED) using FFM.")
public final class GpioOutDemo implements Callable<Integer> {

    static {
        NativeLoader.load();
    }

    /**
     * GPIO device path.
     */
    @Option(names = {"-d", "--device"}, description = "GPIO device", defaultValue = "/dev/gpiochip0")
    private String device;

    /**
     * GPIO line number.
     */
    @Option(names = {"-l", "--line"}, description = "GPIO line", defaultValue = "17")
    private int line;

    /**
     * Delay between toggles in milliseconds.
     */
    @Option(names = {"-m", "--ms"}, description = "Delay in milliseconds", defaultValue = "500")
    private long delay;

    /**
     * Number of toggle iterations.
     */
    @Option(names = {"-i", "--iterations"}, description = "Number of iterations", defaultValue = "5")
    private int iterations;

    /**
     * Execution logic for picocli.
     *
     * @return Exit code.
     */
    @Override
    public Integer call() {
        log.info("Starting GpioOutDemo on {} line {}", device, line);

        try (final var out = new GpioOut(device, line)) {
            for (int i = 0; i < iterations; i++) {
                log.debug("Iteration {} of {}", i + 1, iterations);

                out.on();
                Thread.sleep(delay);

                out.off();
                if (i < iterations - 1) {
                    Thread.sleep(delay);
                }
            }
            log.info("Demo completed successfully.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Demo interrupted: {}", e.getMessage());
            return 1;
        } catch (Exception e) {
            log.error("Execution failed: {}", e.getMessage());
            return 1;
        }
        return 0;
    }

    /**
     * Main entry point.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new GpioOutDemo()).execute(args));
    }
}
