/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.SoftPwm;
import com.codeferm.periphery.device.PwmDevice;
import com.codeferm.periphery.device.PwmLed;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Unified LED Flash demo supporting Hardware and Software PWM.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "LedFlash", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT")
public class LedFlash implements Callable<Integer> {

    static {
        NativeLoader.load();
    }

    @Option(names = {"-m", "--mode"}, description = "Mode: HW or SW.", defaultValue = "HW")
    private String mode;

    @Option(names = {"-d", "--device"}, description = "PWM Chip or GPIO Dev.", defaultValue = "0")
    private String device;

    @Option(names = {"-c", "--channel"}, description = "PWM Channel or GPIO Line.", defaultValue = "0")
    private int channel;

    @Option(names = {"-p", "--period"}, description = "Period in ns.", defaultValue = "1000000")
    private long period;

    @Override
    public Integer call() {
        log.info("Starting LedFlash [Mode: {}, Device: {}, Channel: {}]", mode, device, channel);

        final PwmDevice pwm = mode.equalsIgnoreCase("HW")
                ? new PwmLed(Integer.parseInt(device), channel)
                : new SoftPwm(device.startsWith("/") ? device : "/dev/gpiochip0", channel);

        try (pwm) {
            // Set initial safe state before enabling
            pwm.setPulse(period, 0L);
            pwm.enable();

            for (var i = 0; i < 10; i++) {
                // Fade up
                changeBrightness(pwm, period, 0L, period / 100, 100, 5000);
                // Fade down
                changeBrightness(pwm, period, period, -(period / 100), 100, 5000);
            }

            pwm.disable();
            log.info("LedFlash completed successfully.");
            return 0;
        } catch (final Exception e) {
            log.error("LedFlash failed: {}", e.getMessage());
            return 1;
        }
    }

    private void changeBrightness(final PwmDevice pwm, final long periodNs, final long startDc,
            final long dcInc, final int count, final int sleepUs) throws InterruptedException {
        var currentDc = startDc;
        for (var i = 0; i < count; i++) {
            pwm.setPulse(periodNs, currentDc);
            TimeUnit.MICROSECONDS.sleep(sleepUs);
            currentDc += dcInc;
        }
    }

    public static void main(final String... args) {
        System.exit(new CommandLine(new LedFlash()).execute(args));
    }
}
