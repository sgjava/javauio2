/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.BlockingButton;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Blocking event demo using FFM-based BlockingButton device.
 * <p>
 * This demo demonstrates efficient edge detection polling. It utilizes the 
 * high-level abstraction to wait for physical button presses (interrupts) 
 * without CPU-intensive busy-waiting.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "ButtonWait", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Uses edge detection to wait for button press via FFM.")
public class ButtonWait implements Callable<Integer> {

    static {
        // Load the native library for underlying FFM hardware access
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
     * Polls for edge events until a 10-second timeout occurs.
     *
     * @return Exit code (0 for success, 1 for hardware failure).
     */
    @Override
    public Integer call() {
        var exitCode = 0;
        log.info("Starting ButtonWait on {} line {}", device, line);
        try (final var button = new BlockingButton(device, line)) {
            log.info("Monitoring for edges. Idle 10 seconds to exit.");
            BlockingButton.ButtonEvent event;
            // Use the pre-allocated native buffer via waitForEvent
            while ((event = button.waitForEvent(10000)) != null) {
                final var edgeStr = BlockingButton.edgeToString(event.edge());
                final var timestampStr = BlockingButton.formatTimestamp(event.timestamp());

                // Efficiently log the hardware interrupt event
                switch (edgeStr) {
                    case "Rising" -> 
                        log.info("Edge rising  [{}]", timestampStr);
                    case "Falling" -> 
                        log.info("Edge falling [{}]", timestampStr);
                    default -> 
                        log.info("Invalid edge {}, [{}]", event.edge(), timestampStr);
                }
            }
            log.info("No events detected for 10 seconds. Shutting down.");
        } catch (final RuntimeException e) {
            log.error("Hardware error: {}", e.getMessage());
            exitCode = 1;
        }
        return exitCode;
    }

    /**
     * Main entry point using picocli.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new ButtonWait()).execute(args));
    }
}
