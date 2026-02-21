/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.SysLed;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * LED blink demo using the high-level FFM-based SysLed wrapper.
 * <p>
 * This demo toggles a system LED on and off for a set number of iterations.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "SysLedBlink", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Turn LED on and off using FFM-based SysLed device class.")
public class SysLedBlink implements Callable<Integer> {

    /**
     * Load the native periphery library and register with the ClassLoader. Required for FFM native access [cite: 2026-02-08].
     */
    static {
        NativeLoader.load();
    }

    /**
     * System LED name as found in /sys/class/leds/. Default set to common Pine A64/NanoPi PWR LED.
     */
    @Option(names = {"-n", "--name"}, description = "System LED name, ${DEFAULT-VALUE} by default.",
            defaultValue = "nanopi:green:pwr")
    private String name;

    /**
     * Number of blink iterations.
     */
    @Option(names = {"-c", "--count"}, description = "Number of blinks, ${DEFAULT-VALUE} by default.",
            defaultValue = "10")
    private int count;

    /**
     * Blinks the system LED and restores its original state upon completion.
     *
     * @return Exit code (0 for success, 1 for hardware error).
     * @throws InterruptedException If the blink sleep is interrupted.
     */
    @Override
    public Integer call() throws InterruptedException {
        var exitCode = 0;
        log.info("Starting SysLedBlink on LED: {}", name);
        // SysLed manages the Arena and MemorySegments for zero-allocation I/O [cite: 2026-02-13]
        try (final var sysLed = new SysLed(name)) {
            // Record original state to be a good citizen on exit
            final var originalValue = sysLed.read();
            log.info("LED {} initial state: {}", name, originalValue ? "ON" : "OFF");
            log.info("LED Max Brightness: {}", sysLed.getMaxBrightness());
            for (var i = 0; i < count; i++) {
                log.debug("Blink iteration {}/{}", i + 1, count);
                sysLed.write(true);
                TimeUnit.SECONDS.sleep(1);
                sysLed.write(false);
                TimeUnit.SECONDS.sleep(1);
            }
            // Restore the LED to how we found it
            log.info("Restoring LED to original state: {}", originalValue);
            sysLed.write(originalValue);
        } catch (final RuntimeException e) {
            log.error("Hardware interaction failed: {}", e.getMessage());
            exitCode = 1;
        }

        return exitCode;
    }

    /**
     * Main entry point utilizing picocli for argument parsing.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new SysLedBlink()).execute(args));
    }
}
