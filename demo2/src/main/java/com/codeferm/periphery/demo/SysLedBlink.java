/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.device.SysLed;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * LED blink demo using the high-level FFM-based SysLed wrapper.
 * <p>
 * This demo toggles a system LED on and off for a set number of iterations. Includes explicit state recovery traps to ensure a
 * clean fallback if interrupted.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "SysLedBlink", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Turn LED on and off using FFM-based SysLed device class.")
public class SysLedBlink extends AbstractDemo {

    /**
     * System LED name as found in /sys/class/leds/. Default set to Raspberry PI ACT (green led).
     */
    @Option(names = {"-n", "--name"}, description = "System LED name, ${DEFAULT-VALUE} by default.",
            defaultValue = "ACT")
    private String name;

    /**
     * Number of blink iterations.
     */
    @Option(names = {"-c", "--count"}, description = "Number of blinks, ${DEFAULT-VALUE} by default.",
            defaultValue = "10")
    private int count;

    /**
     * Blinks the system LED and restores its original state upon completion.
     * <p>
     * Explicitly hooks into InterruptedException sequences to guarantee physical hardware cleanup runs before the root unmanaged
     * memory arenas collapse.
     * </p>
     *
     * @return Exit code (0 for success, 1 for hardware error).
     * @throws Exception On hardware or execution error.
     */
    @Override
    public Integer call() throws Exception {
        var exitCode = 0;
        addTerminalHook();
        log.info("Starting SysLedBlink on LED: {}", name);

        // SysLed manages the Arena and MemorySegments for zero-allocation I/O
        try (final var sysLed = new SysLed(name)) {
            // Record original state to be a good citizen on exit
            final var originalValue = sysLed.read();
            log.info("LED {} initial state: {}", name, originalValue ? "ON" : "OFF");
            log.info("LED Max Brightness: {}", sysLed.getMaxBrightness());

            try {
                for (var i = 0; i < count; i++) {
                    log.debug("Blink iteration {}/{}", i + 1, count);
                    sysLed.write(true);
                    TimeUnit.SECONDS.sleep(1);
                    sysLed.write(false);
                    TimeUnit.SECONDS.sleep(1);
                }
            } catch (final InterruptedException e) {
                log.warn("Blink loop broken by system shutdown signal. Cleaning hardware flags...");
                // Restore thread state flag context
                Thread.currentThread().interrupt();
            } finally {
                // Guaranteed safety net line: runs even if SIGINT punches out of the try-block loop
                log.info("Restoring LED to original state: {}", originalValue);
                sysLed.write(originalValue);
            }

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
