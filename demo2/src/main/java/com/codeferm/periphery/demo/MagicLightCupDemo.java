/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.device.MagicLightCup;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Real-Time Polling Single Magic Light Cup Application Demo using Java FFM.
 * <p>
 * This application manages a single KY-027 light cup module. It reads the state of its internal tilt sensor and controls its
 * co-located LED directly within a zero-allocation hot execution loop, extending {@link AbstractDemo} for picocli management and
 * semantic {@link TimeUnit} backoffs.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "MagicLightCupDemo",
        mixinStandardHelpOptions = true,
        version = "2.1.2",
        description = "Monitors a single Magic Light Cup to interactively toggle its onboard LED via FFM and TimeUnit.")
public class MagicLightCupDemo extends AbstractDemo {

    /**
     * GPIO character chip path.
     */
    @Option(names = {"-d", "--device"}, description = "GPIO character chip path.", defaultValue = "/dev/gpiochip0")
    private String device;

    /**
     * GPIO line index for the Module Switch (S).
     */
    @Option(names = {"-c", "--clk", "--sw"}, description = "GPIO line index for Module Switch.", defaultValue = "23")
    private int switchLine;

    /**
     * GPIO line index for the Module LED (L).
     */
    @Option(names = {"-t", "--dt", "--led"}, description = "GPIO line index for Module LED.", defaultValue = "24")
    private int ledLine;

    /**
     * Total duration context runtime in seconds.
     */
    @Option(names = {"-s", "--seconds"}, description = "Total duration context runtime in seconds.", defaultValue = "45")
    private int durationSeconds;

    /**
     * Loop backoff polling delay threshold in milliseconds.
     */
    @Option(names = {"-p", "--poll"}, description = "Hot loop backoff delay in ms.", defaultValue = "15")
    private long pollIntervalMs;

    /**
     * Coordinates the application execution lifecycle.
     *
     * @return 0 on successful processing, 1 on application failure.
     * @throws Exception On hardware or execution error.
     */
    @Override
    public Integer call() throws Exception {
        // Fix terminal formatting on interrupt via inherited base class routine
        addTerminalHook();

        log.info("Starting Single Magic Light Cup Demo on {}", this.device);
        log.info("Module hardware mapping -> [Switch: {}, LED: {}]", this.switchLine, this.ledLine);

        // Instantiate single native driver device wrapper using framework path signatures
        try (final var cup = new MagicLightCup(this.device, this.switchLine, this.ledLine)) {

            log.info("Entering zero-allocation tracking execution loop...");

            // Caching primitive state outside hot loop to achieve zero runtime GC overhead
            var switchTripped = false;
            final var endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(this.durationSeconds);

            // Hot Execution loop boundaries matching your standard timeout constraints
            while (cup.isRunning() && System.currentTimeMillis() < endTime) {

                // Read local switch status using zero-allocation primitives
                switchTripped = cup.readSwitchState();

                // Directly affect the local LED state on the same board
                cup.setLedState(switchTripped);

                // Semantic timing native backoff to stabilize bus chatter without thrashing the heap
                TimeUnit.MILLISECONDS.sleep(this.pollIntervalMs);
            }

            log.info("Demo session window closed cleanly.");
            return 0;
        } catch (final InterruptedException e) {
            // Restore interrupted state cleanly up the call stack
            Thread.currentThread().interrupt();
            log.error("Execution loop interrupted: {}", e.getMessage());
            return 1;
        } catch (final Exception e) {
            log.error("Critical application failure: {}", e.getMessage());
            return 1;
        }
    }

    /**
     * Entry point command-line interface proxy.
     *
     * @param args Command line execution runtime arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new MagicLightCupDemo()).execute(args));
    }
}
