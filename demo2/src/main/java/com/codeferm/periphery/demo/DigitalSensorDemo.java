/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.device.DigitalSensor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Universal high-performance demo for digital sensors with configurable execution lifespan.
 * <p>
 * Utilizes Java FFM via {@link DigitalSensor} to monitor hardware state transitions. Supports parametric configuration for polling,
 * lockout windows, and execution duration.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.2.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "DigitalSensorDemo",
        mixinStandardHelpOptions = true,
        version = "1.0.0",
        description = "Monitors digital state transitions using FFM-backed GPIO.")
public final class DigitalSensorDemo extends AbstractDemo {

    /**
     * GPIO character device path.
     */
    @Option(names = {"-d", "--device"}, description = "GPIO device path.", defaultValue = "/dev/gpiochip0")
    private String device;

    /**
     * GPIO line number.
     */
    @Option(names = {"-l", "--line"}, description = "GPIO line number.", defaultValue = "17")
    private int line;

    /**
     * Sampling frequency in milliseconds.
     */
    @Option(names = {"-p", "--poll"}, description = "Poll interval (ms).", defaultValue = "10")
    private long pollInterval;

    /**
     * Trailing window to ignore chatter (ms).
     */
    @Option(names = {"-w", "--window"}, description = "Lockout window (ms).", defaultValue = "200")
    private long lockout;

    /**
     * Human-readable label for logs.
     */
    @Option(names = {"-n", "--name"}, description = "Sensor label.", defaultValue = "Generic")
    private String sensorName;

    /**
     * Total duration to run the demo before exiting.
     */
    @Option(names = {"-t", "--timeout"}, description = "Execution timeout in seconds.", defaultValue = "60")
    private long timeout;

    /**
     * Orchestrates the sensor lifecycle based on CLI parameters.
     *
     * @return Exit code (0 for success, 1 for failure).
     */
    @Override
    public Integer call() {
        // Add shutdown hook to fix terminal on Ctrl+C via base class
        addTerminalHook();

        log.info("Starting {} monitor on {} Line {} for {}s", sensorName, device, line, timeout);
        final var startTime = System.nanoTime();
        final var timeoutNanos = TimeUnit.SECONDS.toNanos(timeout);

        // Standard resource management via try-with-resources
        try (final var sensor = new DigitalSensor(device, line)) {
            // Initiate the non-blocking watch thread
            sensor.watch(pollInterval, lockout, state -> {
                final var stateLabel = (state == 0) ? "HIGH" : "LOW";
                log.info("[{}] State: {}", sensorName, stateLabel);
            });

            log.info("Monitoring active. Auto-closing in {} seconds...", timeout);

            // Loop until interrupted or timeout reached
            while (!Thread.currentThread().isInterrupted()) {
                final var elapsed = System.nanoTime() - startTime;
                if (elapsed >= timeoutNanos) {
                    log.info("Timeout of {}s reached. Cleaning up...", timeout);
                    break;
                }
                // Sleep increment allows responsive termination via SIGINT
                TimeUnit.MILLISECONDS.sleep(500);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Demo interrupted by user.");
        } catch (final Exception e) {
            log.error("Execution failed: {}", e.getMessage());
            return 1;
        }

        log.info("Demo exited cleanly.");
        return 0;
    }

    /**
     * CLI entry point.
     *
     * @param args Array of command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new DigitalSensorDemo()).execute(args));
    }
}
