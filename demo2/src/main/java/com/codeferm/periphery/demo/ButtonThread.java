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
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Blocking event demo using a separate thread for edge detection.
 * <p>
 * This demo demonstrates how to offload hardware polling to a background thread using an {@link ExecutorService}, allowing the main
 * thread to perform other logic concurrently.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "ButtonThread", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Waits for button press in a separate thread while other processing occurs.")
public class ButtonThread implements Callable<Integer> {

    static {
        // Load native library for the FFM-based hardware devices
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
     * Spawns a background task to wait for hardware edge events.
     *
     * @param executor Executor service to run the polling task.
     */
    public void executeWaitForEdge(final ExecutorService executor) {
        executor.execute(() -> {
            log.info("Background thread monitoring {} line {}", device, line);
            // BlockingButton manages the FFM Arena and handle internally
            try (final var button = new BlockingButton(device, line)) {
                log.info("Press button (10s idle timeout to exit background task)");
                BlockingButton.ButtonEvent event;
                // Zero-allocation read loop
                while ((event = button.waitForEvent(10000)) != null) {
                    final var edgeStr = BlockingButton.edgeToString(event.edge());
                    final var timestampStr = BlockingButton.formatTimestamp(event.timestamp());
                    switch (edgeStr) {
                        case "Rising" ->
                            log.info("Edge rising  [{}]", timestampStr);
                        case "Falling" ->
                            log.info("Edge falling [{}]", timestampStr);
                        default ->
                            log.info("Invalid edge {}, [{}]", event.edge(), timestampStr);
                    }
                }
                log.info("Background task timed out.");
            } catch (final Exception e) {
                log.error("Hardware error in background thread: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Main task logic.
     *
     * @return Exit code.
     */
    @Override
    public Integer call() {
        var exitCode = 0;
        final var executor = Executors.newSingleThreadExecutor();
        executeWaitForEdge(executor);
        try {
            // Signal shutdown so the executor stops accepting new tasks
            executor.shutdown();
            // Simulate main loop processing for 30 seconds
            int count = 0;
            while (count < 30 && !executor.isTerminated()) {
                log.info("Main program busy... (Iteration {})", count + 1);
                TimeUnit.SECONDS.sleep(1);
                count++;
            }
            if (!executor.isTerminated()) {
                log.info("Main loop finished; waiting for background thread to conclude...");
                executor.awaitTermination(Long.MAX_VALUE, NANOSECONDS);
            }
        } catch (final InterruptedException e) {
            log.error("Main thread interrupted");
            Thread.currentThread().interrupt();
            exitCode = 1;
        } finally {
            // Ensure resource cleanup
            executor.shutdownNow();
        }
        log.info("Main program exiting.");
        return exitCode;
    }

    /**
     * Entry point using picocli.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new ButtonThread()).execute(args));
    }
}
