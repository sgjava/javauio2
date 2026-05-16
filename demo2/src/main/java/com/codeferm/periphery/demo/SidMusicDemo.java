/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.device.PassiveSpeaker;
import com.codeferm.periphery.device.PwmDeviceFactory;
import com.codeferm.periphery.sound.SidVoice;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * SID Music Sequencer Demo using a unified FFM-backed PWM transport.
 * <p>
 * This demo utilizes the {@link SidVoice} simulator to play a melody with ADSR (Attack, Decay, Sustain, Release) characteristics.
 * It demonstrates high-precision timing using nanosecond spin-wait loops and the Single-Ownership pattern for native resource
 * management.
 * </p>
 * <p>
 * The {@link PassiveSpeaker} owns the lifecycle of the factory-created {@code PwmDevice}, ensuring that hardware is silenced and
 * native memory is unmapped exactly once upon completion or failure.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "SidMusicDemo",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "Plays a melody using the SID ADSR voice simulator.")
public final class SidMusicDemo extends AbstractDemo {

    /**
     * Operation mode: HW (Hardware Sysfs) or SW (Software GPIO Bit-bang).
     */
    @Option(names = {"-m", "--mode"}, description = "Mode: HW or SW.", defaultValue = "HW")
    private String mode;

    /**
     * Hardware PWM chip index or Software GPIO chip device path.
     */
    @Option(names = {"-d", "--device"}, description = "PWM chip index or GPIO chip path.", defaultValue = "0")
    private String device;

    /**
     * Hardware PWM channel or Software GPIO line index.
     */
    @Option(names = {"-c", "--channel"}, description = "PWM channel or GPIO line index.", defaultValue = "0")
    private int channel;

    /**
     * Synthesis engine update frequency (1000Hz = 1ms resolution).
     */
    private static final long TICKS_PER_SECOND = 1000L;

    /**
     * Internal tick duration in nanoseconds.
     */
    private static final long TICK_NS = 1_000_000_000L / TICKS_PER_SECOND;

    /**
     * Sample melody (C4 to C5 Major scale).
     */
    private static final double[] MELODY = {
        261.63, 293.66, 329.63, 349.23, 392.00, 440.00, 493.88, 523.25
    };

    /**
     * Orchestrates the SID music sequence.
     * <p>
     * Initializes the transport and speaker, configures the ADSR envelope, and iterates through the melody with precise timing.
     * </p>
     *
     * @return Exit code (0 for success, 1 for error).
     * @throws Exception On hardware or execution error.
     */
    @Override
    public Integer call() throws Exception {
        addTerminalHook();
        log.info("Starting SID Music Sequencer [Mode: {}, Device: {}, Channel: {}]", mode, device, channel);

        // Single Ownership. Speaker manages the PwmDevice transport lifecycle.
        try (final var speaker = new PassiveSpeaker(PwmDeviceFactory.create(mode, device, channel))) {
            // Set safety period and enable output
            speaker.setPulse(1_000_000, 0);
            speaker.enable();

            final var voice = new SidVoice(speaker, TICKS_PER_SECOND);
            // Set classic "pluck" ADSR: Fast attack, medium decay, low sustain, fast release
            voice.setAdsr(0.01, 0.1, 0.3, 0.05);

            final var startTime = System.nanoTime();
            var tickCount = 0L;

            for (final var noteFreq : MELODY) {
                log.info("Playing Note: {} Hz", noteFreq);
                // Gate ON: Begin Attack/Decay/Sustain phase
                voice.gateOn(noteFreq);
                tickCount = runVoice(voice, tickCount, startTime, 400);

                // Gate OFF: Begin Release phase
                voice.gateOff();
                tickCount = runVoice(voice, tickCount, startTime, 100);
            }

            log.info("Performance complete.");
            return 0;
        } catch (final Exception e) {
            log.error("Sequencer failure: {}", e.getMessage());
            return 1;
        }
    }

    /**
     * Runs the SID voice engine for a specified duration using nanosecond precision syncing.
     * <p>
     * Uses {@link Thread#onSpinWait()} to minimize jitter during the timing loop, ensuring that synthesis updates occur at exact
     * 1ms intervals.
     * </p>
     *
     * @param voice The SID voice engine to tick.
     * @param startTick The cumulative tick count from the start of the performance.
     * @param startNanos The system nanosecond time when the performance began.
     * @param durationMs The duration to run this segment in milliseconds.
     * @return The updated cumulative tick count.
     */
    private long runVoice(final SidVoice voice, final long startTick, final long startNanos, final int durationMs) {
        var currentTick = startTick;
        final var endTick = startTick + durationMs;

        while (currentTick < endTick) {
            final var targetTime = startNanos + (currentTick * TICK_NS);

            // Precision sync: Busy-wait until target time is reached
            while (System.nanoTime() < targetTime) {
                Thread.onSpinWait();
            }

            // Update synthesis envelope and hardware pulse width
            voice.tick();
            currentTick++;
        }
        return currentTick;
    }

    /**
     * CLI entry point for the SID Music Demo.
     *
     * @param args Command line arguments passed to picocli.
     */
    public static void main(final String[] args) {
        System.exit(new CommandLine(new SidMusicDemo()).execute(args));
    }
}
