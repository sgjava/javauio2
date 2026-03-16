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

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Blocking event demo using FFM-based {@link BlockingButton}.
 * <p>
 * This version demonstrates concurrent processing by running the blocking hardware event loop in a separate thread, allowing the
 * main program to perform other tasks.
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
     * GPIO device path option.
     */
    @Option(names = {"-d", "--device"}, description = "GPIO device path, ${DEFAULT-VALUE} by default.")
    private String device = "/dev/gpiochip0";

    /**
     * GPIO line number option.
     */
    @Option(names = {"-l", "--line"}, description = "GPIO line number, ${DEFAULT-VALUE} by default.")
    private int line = 77;

    /**
     * Executes the blocking wait for GPIO edges in a background thread.
     *
     * @param executor The {@link ExecutorService} used to run the background task.
     */
    public void executeWaitForEdge(final ExecutorService executor) {
        executor.execute(() -> {
            log.info("Starting background edge detection on {} line {}", device, line);
            try (final var button = new BlockingButton(device, line)) {
                log.info("Press button to see events. Stop pressing for 10 seconds to exit thread.");

                BlockingButton.ButtonEvent event;
                // Loop until waitForEvent times out (returns null after 10s)
                while ((event = button.waitForEvent(10000)) != null) {
                    final var edgeStr = BlockingButton.edgeToString(event.edge());
                    final var timestampStr = BlockingButton.formatTimestamp(event.timestamp());

                    log.info("Button Edge: {:<8} [Timestamp: {}]", edgeStr, timestampStr);
                }
                log.info("Background thread exiting due to inactivity timeout.");
            } catch (Exception e) {
                log.error("Hardware error in background thread: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Main application logic.
     * <p>
     * Orchestrates the background thread and simulates primary application work.
     * </p>
     *
     * @return Exit code.
     */
    @Override
    public Integer call() {
        var exitCode = 0;
        final var executor = Executors.newSingleThreadExecutor();

        try {
            executeWaitForEdge(executor);

            // Initiate shutdown so the executor stops accepting new tasks
            executor.shutdown();

            // Simulate main application work for 30 seconds or until thread terminates
            int count = 0;
            while (count < 30 && !executor.isTerminated()) {
                log.info("Main program processing... (Iteration {})", count + 1);
                TimeUnit.SECONDS.sleep(1);
                count++;
            }

            if (!executor.isTerminated()) {
                log.info("Main work complete. Waiting for background thread to finish...");
                executor.awaitTermination(Long.MAX_VALUE, NANOSECONDS);
            }

        } catch (InterruptedException e) {
            log.error("Execution interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
            exitCode = 1;
        } finally {
            // Ensure resources are cleaned up
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
