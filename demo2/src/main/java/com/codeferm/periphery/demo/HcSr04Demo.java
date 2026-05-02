/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.HcSr04;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * HC-SR04 FFM Ultrasonic Distance sensor demo with configurable intervals.
 * <p>
 * NOTE: The Echo pin (5V) must be connected to the Raspberry Pi GPIO (3.3V) using a voltage divider: 1k ohm resistor from Echo to
 * GPIO, and a 2k ohm resistor from GPIO to Ground.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "HcSr04Demo", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT")
public class HcSr04Demo implements Callable<Integer> {

    static {
        // Load native library for the underlying Periphery FFM calls
        NativeLoader.load();
    }

    @Option(names = {"-t", "--trig"}, description = "Trig line.", defaultValue = "27")
    private int trigLine;

    @Option(names = {"-e", "--echo"}, description = "Echo line.", defaultValue = "17")
    private int echoLine;

    @Option(names = {"-m", "--metric"}, description = "Use metric (cm) instead of imperial (in).", defaultValue = "false")
    private boolean metric;

    @Option(names = {"-s", "--seconds"}, description = "Duration to run in seconds.", defaultValue = "60")
    private int durationSeconds;

    @Option(names = {"-i", "--interval"}, description = "Interval between reads in seconds.", defaultValue = "1")
    private int intervalSeconds;

    /**
     * Executes the distance measurement loop with specified interval and duration.
     *
     * @return 0 on success, 1 on failure.
     */
    @Override
    public Integer call() {
        log.info("Starting HC-SR04 Demo [Trig: {}, Echo: {}]", trigLine, echoLine);
        log.info("Runtime: {}s, Interval: {}s, Units: {}",
                durationSeconds, intervalSeconds, metric ? "Metric (cm)" : "Imperial (in)");

        try (final var sensor = new HcSr04("/dev/gpiochip0", trigLine, "/dev/gpiochip0", echoLine)) {
            final var startTime = System.currentTimeMillis();
            final var endTime = startTime + TimeUnit.SECONDS.toMillis(durationSeconds);

            while (System.currentTimeMillis() < endTime) {
                if (sensor.read()) {
                    final var rawCm = sensor.getDistance();

                    if (metric) {
                        log.info("Distance: {} cm", String.format("%.2f", rawCm));
                    } else {
                        // 1 cm = 0.393701 inches
                        final var inches = rawCm * 0.393701;
                        log.info("Distance: {} in", String.format("%.2f", inches));
                    }
                } else {
                    log.warn("Read failed: Pulse timeout.");
                }

                // Wait for the specified interval before the next measurement
                if (System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(intervalSeconds) < endTime) {
                    TimeUnit.SECONDS.sleep(intervalSeconds);
                } else {
                    break;
                }
            }

            log.info("Demo completed successfully.");
            return 0;
        } catch (final InterruptedException e) {
            log.error("Demo interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return 1;
        } catch (final Exception e) {
            log.error("Demo failed: {}", e.getMessage());
            return 1;
        }
    }

    /**
     * Entry point for the HC-SR04 demo.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new HcSr04Demo()).execute(args));
    }
}
