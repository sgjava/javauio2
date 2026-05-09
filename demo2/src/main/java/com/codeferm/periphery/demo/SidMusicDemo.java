/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.PassiveSpeaker;
import com.codeferm.periphery.sound.SidVoice;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * SID Music Sequencer Demo.
 * <p>
 * Plays a pre-defined melody using the {@link SidVoice} simulator. Demonstrates precise timing of Gate ON/OFF events to create a
 * musical performance.</p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "SidMusicDemo", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Plays a melody using the SID ADSR voice simulator.")
public final class SidMusicDemo implements Callable<Integer> {

    static {
        NativeLoader.load();
    }

    /**
     * Update frequency for the synthesis engine (1000Hz = 1ms resolution).
     */
    private static final long TICKS_PER_SECOND = 1000L;
    private static final long TICK_NS = 1_000_000_000L / TICKS_PER_SECOND;

    /**
     * Simple melody: C4, D4, E4, F4, G4, A4, B4, C5.
     */
    private static final double[] MELODY = {
        261.63, 293.66, 329.63, 349.23, 392.00, 440.00, 493.88, 523.25
    };

    /**
     * Executes the song sequencer.
     *
     * @return Exit code.
     */
    @Override
    public Integer call() {
        log.info("Starting SID Music Sequencer...");
        try (final var speaker = new PassiveSpeaker(0, 0)) {
            // Satisfy Pi driver: set period before enabling
            speaker.setPulse(1_000_000, 0);
            speaker.enable();
            final var voice = new SidVoice(speaker, TICKS_PER_SECOND);
            // Set classic "pluck" ADSR: Fast attack, medium decay, low sustain, fast release
            voice.setAdsr(0.01, 0.1, 0.3, 0.05);
            final var startTime = System.nanoTime();
            var tickCount = 0L;
            for (final var noteFreq : MELODY) {
                log.info("Playing Note: {} Hz", noteFreq);
                // Gate ON
                voice.gateOn(noteFreq);
                // Hold note for 400ms
                tickCount = this.runVoice(voice, tickCount, startTime, 400);
                // Gate OFF
                voice.gateOff();
                // Let release ring for 100ms
                tickCount = this.runVoice(voice, tickCount, startTime, 100);
            }
            log.info("Song complete.");

        } catch (final Exception e) {
            log.error("Sequencer failure: {}", e.getMessage());
            return 1;
        }
        return 0;
    }

    /**
     * Runs the voice tick loop for a specific number of milliseconds.
     *
     * @param voice The SID voice engine.
     * @param startTick The current global tick offset.
     * @param startNanos The absolute start time of the performance.
     * @param durationMs How long to run the loop.
     * @return The updated global tick offset.
     */
    private long runVoice(final SidVoice voice, final long startTick, final long startNanos, final int durationMs) {
        var currentTick = startTick;
        final var endTick = startTick + durationMs;
        while (currentTick < endTick) {
            final var targetTime = startNanos + (currentTick * TICK_NS);
            // Precision sync
            while (System.nanoTime() < targetTime) {
                Thread.onSpinWait();
            }
            // Update synthesis and hardware
            voice.tick();
            currentTick++;
        }
        return currentTick;
    }

    /**
     * Main entry point.
     *
     * @param args CLI arguments.
     */
    public static void main(final String[] args) {
        final var exitCode = new CommandLine(new SidMusicDemo()).execute(args);
        System.exit(exitCode);
    }
}
