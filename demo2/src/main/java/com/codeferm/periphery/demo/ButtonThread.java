/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.BlockingButton;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


/**
 * Concurrent blocking event demo using FFM-based {@link BlockingButton}.
 * <p>
 * This demo runs the hardware edge detection loop in a background thread, allowing the main application loop to remain responsive.
 * It demonstrates proper thread interruption and resource cleanup.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "ButtonThread", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Uses FFM edge detection to wait for button press while other processing occurs.")
public class ButtonThread implements Callable<Integer> {

    static {
        // Load the native library for underlying FFM hardware access
        NativeLoader.load();
    }

    /**
     * GPIO device path.
     */
    @Option(names = {"-d", "--device"}, description = "GPIO device path, ${DEFAULT-VALUE} by default.",
            defaultValue = "/dev/gpiochip0")
    private String device;

    /**
     * GPIO line number.
     */
    @Option(names = {"-l", "--line"}, description = "GPIO line number, ${DEFAULT-VALUE} by default.",
            defaultValue = "18")
    private int line;

    /**
     * Debounce time in milliseconds.
     */
    @Option(names = {"-b", "--debounce"}, description = "Debounce time in ms, ${DEFAULT-VALUE} by default.",
            defaultValue = "50")
    private int debounce;

    /**
     * Executes the blocking wait for GPIO edges in a background thread.
     * <p>
     * The task will terminate if the button is idle for 10 seconds or if the executor service is shut down.
     * </p>
     *
     * @param executor The {@link ExecutorService} used to run the background task.
     */
    public void executeWaitForEdge(final ExecutorService executor) {
        executor.execute(() -> {
            log.info("Starting background edge detection on {} line {}", device, line);
            try (final var button = new BlockingButton(device, line)) {
                button.setDebounceMillis(debounce);
                log.info("Press button to see events. Idle 10 seconds to exit thread.");

                BlockingButton.ButtonEvent event;
                // Loop until waitForEvent times out (returns null after 10s)
                while ((event = button.waitForEvent(10000)) != null) {
                    final var edgeStr = BlockingButton.edgeToString(event.edge());
                    final var timestampStr = BlockingButton.formatTimestamp(event.timestamp());

                    // Corrected SLF4J formatting
                    log.info("Button Event: {} at timestamp {}", edgeStr, timestampStr);
                }
                log.info("Background thread exiting due to inactivity timeout.");
            } catch (final Exception e) {
                log.error("Hardware error in background thread: {}", e.getMessage());
            }
        });
    }

    /**
     * Main application logic orchestrating the background thread and work simulation.
     *
     * @return Exit code (0 for success, 1 for error).
     */
    @Override
    public Integer call() {
        var exitCode = 0;
        final var executor = Executors.newSingleThreadExecutor();

        try {
            executeWaitForEdge(executor);

            // Initiate shutdown so the executor stops accepting new tasks
            executor.shutdown();

            // Simulate main application work for 30 seconds or until background thread terminates
            var count = 0;
            while (count < 30 && !executor.isTerminated()) {
                log.info("Main program processing... (Iteration {})", ++count);
                TimeUnit.SECONDS.sleep(1);
            }

            if (!executor.isTerminated()) {
                log.info("Main work complete. Waiting for background thread to finish...");
                if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                    log.warn("Background thread timed out. Forcing shutdown.");
                    executor.shutdownNow();
                }
            }

        } catch (final InterruptedException e) {
            log.error("Execution interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
            exitCode = 1;
        } finally {
            if (!executor.isTerminated()) {
                executor.shutdownNow();
            }
        }

        log.info("Application shut down cleanly.");
        return exitCode;
    }

    /**
     * Main entry point for the ButtonThread demo.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        final var cmd = new CommandLine(new ButtonThread());
        System.exit(cmd.execute(args));
    }
}
