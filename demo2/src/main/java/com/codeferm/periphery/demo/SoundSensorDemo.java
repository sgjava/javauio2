/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.SoundSensor;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * 37-in-1 Microphone Module Demo using Java FFM.
 * <p>
 * This app executes a high-performance monitoring wrapper over the digital output (D0) line of an LM393 microphone module. The
 * class configuration defaults to line 17 on the primary GPIO chip to match verified hardware benches.
 * </p>
 * <p>
 * Hardware Tuning Instructions: Meticulously turn the multi-turn potentiometer clockwise to increase sensitivity until the data LED
 * illuminates under ambient silence, then ease counter-clockwise slightly until the LED just extinguishes.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "SoundSensorDemo", mixinStandardHelpOptions = true, version = "1.0.5")
public class SoundSensorDemo implements Callable<Integer> {

    static {
        // Load native library for underlying Periphery FFM calls [2026-02-24]
        NativeLoader.load();
    }

    @Option(names = {"-d", "--device"}, description = "GPIO device.", defaultValue = "/dev/gpiochip0")
    private String device;

    @Option(names = {"-l", "--line"}, description = "GPIO line.", defaultValue = "17")
    private int line;

    @Option(names = {"-p", "--poll"}, description = "Poll interval in ms.", defaultValue = "2")
    private long pollMs;

    @Option(names = {"-b", "--lockout"}, description = "Trailing lockout period in ms.", defaultValue = "50")
    private long lockoutMs;

    @Option(names = {"-s", "--seconds"}, description = "Duration to run.", defaultValue = "30")
    private int durationSeconds;

    /**
     * Executes the sound monitoring loop.
     *
     * @return 0 on success, 1 on failure.
     */
    @Override
    public Integer call() {
        log.info("Starting Sound Sensor Demo on {} line {}", this.device, this.line);
        log.info("Settings: {}ms poll, {}ms trailing lockout, {}s duration",
                this.pollMs, this.lockoutMs, this.durationSeconds);
        final var soundCount = new AtomicInteger(0);
        try (final var sensor = new SoundSensor(this.device, this.line)) {
            // Start the background watch thread utilizing zero-allocation polling [2026-02-13]
            sensor.watch(this.pollMs, this.lockoutMs, (final Integer value) -> {
                if (value == 1) {
                    final var count = soundCount.incrementAndGet();
                    log.info("[{}] SOUND DETECTED!", count);
                } else {
                    log.debug("Quiet threshold restored.");
                }
            });
            // Keep the main execution thread alive for the monitored tracking duration
            TimeUnit.SECONDS.sleep(this.durationSeconds);
            log.info("Demo finished. Total sound events detected: {}", soundCount.get());
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
     * Entry point for running the sound sensor evaluation loop.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new SoundSensorDemo()).execute(args));
    }
}
