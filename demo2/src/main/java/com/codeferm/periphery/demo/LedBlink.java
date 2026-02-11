/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle; // Use the generated handle class directly
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.lang.foreign.Arena;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Blink LED using FFM (Foreign Function & Memory API) and c-periphery.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "LedBlink", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Blink LED using FFM bindings.")
public class LedBlink implements Callable<Integer> {

    static {
        // Load the native library before any FFM calls
        NativeLoader.load();
    }

    @Option(names = {"-d", "--device"}, description = "GPIO device, ${DEFAULT-VALUE} by default.")
    private String device = "/dev/gpiochip0";

    @Option(names = {"-l", "--line"}, description = "GPIO line, ${DEFAULT-VALUE} by default.")
    private int line = 77;

    @Override
    public Integer call() {
        // Use Arena to manage the lifecycle of native handles and strings
        try (var arena = Arena.ofConfined()) {
            // Allocate the gpio_handle struct using the jextract-generated layout
            var handle = arena.allocate(gpio_handle.layout());
            var cDevice = arena.allocateFrom(device);

            log.info("Blinking LED on {} line {}", device, line);

            // Open GPIO for output: 1 = GPIO_DIR_OUT (initialized to low)
            if (Periphery.gpio_open(handle, cDevice, line, 1) < 0) {
                log.error("Failed to open GPIO for write");
                return 1;
            }

            try {
                for (var i = 0; i < 10; i++) {
                    log.debug("Cycle {}: LED ON", i);
                    Periphery.gpio_write(handle, true);
                    TimeUnit.SECONDS.sleep(1);

                    log.debug("Cycle {}: LED OFF", i);
                    Periphery.gpio_write(handle, false);
                    TimeUnit.SECONDS.sleep(1);
                }
            } finally {
                // Ensure the GPIO is closed even if sleep is interrupted
                Periphery.gpio_close(handle);
            }

        } catch (InterruptedException e) {
            log.error("LED blink interrupted", e);
            Thread.currentThread().interrupt(); // Restore interrupted status
            return 1;
        } catch (Exception e) {
            log.error("LED blink failed", e);
            return 1;
        }
        return 0;
    }

    public static void main(String... args) {
        var exitCode = new CommandLine(new LedBlink()).execute(args);
        System.exit(exitCode);
    }
}
