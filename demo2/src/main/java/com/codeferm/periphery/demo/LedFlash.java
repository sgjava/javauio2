/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.device.PwmDeviceFactory;
import com.codeferm.periphery.device.PwmLed;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Unified LED Flash and Fade demo supporting Hardware and Software PWM.
 * <p>
 * This demo utilizes the Single-Ownership pattern to manage native FFM resources. The {@link PwmLed} wrapper assumes ownership of
 * the injected transport, ensuring that the hardware is safely disabled and native memory is unmapped exactly once.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "LedFlash",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "Flashes and fades an LED using hardware or software PWM.")
public final class LedFlash extends AbstractDemo {

    /**
     * Operation mode: HW (Hardware Sysfs) or SW (Software GPIO Bit-bang).
     */
    @Option(names = {"-m", "--mode"}, description = "Mode: HW or SW.", defaultValue = "HW")
    private String mode;

    /**
     * Hardware PWM chip index or Software GPIO chip device path.
     */
    @Option(names = {"-d", "--device"}, description = "PWM Chip or GPIO Dev.", defaultValue = "0")
    private String device;

    /**
     * Hardware PWM channel or Software GPIO line index.
     */
    @Option(names = {"-c", "--channel"}, description = "PWM Channel or GPIO Line.", defaultValue = "0")
    private int channel;

    /**
     * Base period for the PWM signal in nanoseconds.
     */
    @Option(names = {"-p", "--period"}, description = "Period in ns.", defaultValue = "1000000")
    private long period;

    /**
     * Orchestrates the LED fading sequence.
     * <p>
     * Initializes the transport via the factory and wraps it in the device-level abstraction. Uses a single-ownership
     * try-with-resources block.
     * </p>
     *
     * @return Exit code (0 for success, 1 for failure).
     */
    @Override
    public Integer call() {
        // Clean up terminal on interrupt
        addTerminalHook();

        log.info("Starting LedFlash [Mode: {}, Device: {}, Channel: {}]", mode, device, channel);

        // Single Ownership. PwmLed manages the PwmDevice transport lifecycle.
        try (final var led = new PwmLed(PwmDeviceFactory.create(mode, device, channel))) {
            // Set initial safe state (Off) and enable output
            led.setPulse(period, 0L);
            led.enable();

            for (var i = 0; i < 10; i++) {
                log.debug("Fade cycle: {}", i + 1);
                // Fade up
                changeBrightness(led, period, 0L, period / 100, 100, 5000);
                // Fade down
                changeBrightness(led, period, period, -(period / 100), 100, 5000);
            }

            led.disable();
            log.info("LedFlash completed successfully.");
            return 0;
        } catch (final Exception e) {
            log.error("LedFlash failure: {}", e.getMessage());
            return 1;
        }
    }

    /**
     * Iteratively changes LED brightness to create a fade effect using microsecond-precision sleeping.
     *
     * @param led The PwmLed device instance.
     * @param periodNs Signal period in nanoseconds.
     * @param startDc Starting duty cycle in nanoseconds.
     * @param dcInc Duty cycle increment/decrement per step.
     * @param count Number of steps in the fade sequence.
     * @param sleepUs Sleep duration in microseconds between steps.
     * @throws InterruptedException If the thread is interrupted during sleep.
     */
    private void changeBrightness(final PwmLed led, final long periodNs, final long startDc,
            final long dcInc, final int count, final int sleepUs) throws InterruptedException {
        var currentDc = startDc;
        for (var i = 0; i < count; i++) {
            led.setPulse(periodNs, currentDc);
            TimeUnit.MICROSECONDS.sleep(sleepUs);
            currentDc += dcInc;
        }
    }

    /**
     * Main entry point for the picocli command.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new LedFlash()).execute(args));
    }
}
