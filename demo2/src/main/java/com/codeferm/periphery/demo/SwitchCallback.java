/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.device.GpioSwitch;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Demo of debounced event-driven GPIO monitoring via FFM.
 * <p>
 * This demo utilizes {@link GpioSwitch} to monitor a pin using a background thread. It provides a clean, interrupt-like programming
 * model for pins that lack hardware EINT support.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "SwitchCallback", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Demonstrates debounced callbacks for GPIO state changes.")
public class SwitchCallback extends AbstractDemo {

    /**
     * GPIO device path.
     */
    @Option(names = {"-d", "--device"}, description = "GPIO device path.",
            defaultValue = "/dev/gpiochip0")
    private String device;

    /**
     * GPIO line number.
     */
    @Option(names = {"-l", "--line"}, description = "GPIO line number.",
            defaultValue = "77")
    private int line;

    /**
     * Polling frequency for the background thread.
     */
    @Option(names = {"-p", "--poll"}, description = "Poll interval in ms.",
            defaultValue = "10")
    private long poll;

    /**
     * Debounce duration to filter mechanical chatter.
     */
    @Option(names = {"-b", "--debounce"}, description = "Debounce duration in ms.",
            defaultValue = "50")
    private long debounce;

    /**
     * Configures the watch thread and monitors for state changes.
     *
     * @return Exit code (0 for success).
     * @throws Exception On hardware or execution error.
     */
    @Override
    public Integer call() throws Exception {
        var exitCode = 0;
        addTerminalHook();

        // Latch to keep the main thread alive while the daemon thread polls
        final var latch = new CountDownLatch(1);
        log.info("Starting SwitchCallback on {} line {}", device, line);

        try (final var sw = new GpioSwitch(device, line)) {
            // Register callback with the correct 3-argument signature:
            // long pollIntervalMs, long debounceMs, Consumer<Integer> callback
            sw.watch(poll, debounce, (final Integer state) -> {
                log.info("STABLE EVENT: Switch is now {}",
                        state == 1 ? "PRESSED" : "OPEN");
            });

            log.info("Monitoring (Poll: {}ms, Debounce: {}ms). Demo exits in 30s.", poll, debounce);

            // Keep main thread alive for 30 seconds to observe events
            if (latch.await(30, TimeUnit.SECONDS)) {
                log.info("Latch triggered.");
            } else {
                log.info("Timed out waiting for events.");
            }
        } catch (final Exception e) {
            log.error("Fatal error during execution: {}", e.getMessage());
            exitCode = 1;
        }

        log.info("SwitchCallback demo concluding.");
        return exitCode;
    }

    /**
     * Entry point using picocli.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new SwitchCallback()).execute(args));
    }
}
