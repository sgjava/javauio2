/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.sound;

import com.codeferm.periphery.device.PassiveSpeaker;
import lombok.extern.slf4j.Slf4j;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Monophonic sound and music manager modeling Atari 2600 Missile Command audio.
 * <p>
 * Ensures non-overlapping playback by automatically canceling active sound effects when a new request is made.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class SoundManager implements AutoCloseable {

    /**
     * The passive speaker transport.
     */
    private final PassiveSpeaker speaker;

    /**
     * Single-thread executor for queuing and running sound effects asynchronously.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        final var thread = new Thread(r, "Sound-Manager");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Random generator for noise-based effects like explosions.
     */
    private final Random random = new Random();

    /**
     * Handle for the currently active sound task.
     */
    private volatile Future<?> activeTask;

    /**
     * Constructs a SoundManager with a given passive speaker.
     *
     * @param speaker The initialized passive speaker instance.
     */
    public SoundManager(final PassiveSpeaker speaker) {
        this.speaker = speaker;
    }

    /**
     * Cancels any currently playing sound and executes a new sound task exclusively.
     *
     * @param soundTask The sound sequence to run.
     */
    private synchronized void playExclusive(final Runnable soundTask) {
        if (activeTask != null) {
            activeTask.cancel(true);
        }
        activeTask = executor.submit(() -> {
            try {
                speaker.enable();
                soundTask.run();
            } catch (final Exception e) {
                if (!(e instanceof InterruptedException)) {
                    log.error("Error during sound playback: {}", e.getMessage());
                }
                Thread.currentThread().interrupt();
            } finally {
                try {
                    speaker.disable();
                } catch (final Exception ignored) {
                }
            }
        });
    }

    /**
     * Plays the classic Missile Command launch sound (rising pitch sweep).
     */
    public void playFire() {
        playExclusive(() -> {
            try {
                // Sweep upward from 200 Hz to 800 Hz
                for (var freq = 200.0; freq < 800.0; freq += 60.0) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    final var periodNs = (long) (1_000_000_000.0 / freq);
                    speaker.setPulse(periodNs, periodNs / 2);
                    Thread.sleep(12);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Plays the explosion sound effect (wide-band pseudo-noise rumble).
     */
    public void playExplosion() {
        playExclusive(() -> {
            try {
                final var durationMs = 350L;
                final var startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < durationMs) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    // Deep rumbling noise range 50 Hz to 350 Hz
                    final var freq = 50.0 + random.nextDouble() * 300.0;
                    final var periodNs = (long) (1_000_000_000.0 / freq);
                    speaker.setPulse(periodNs, periodNs / 2);
                    Thread.sleep(10);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Plays the game start / wave start alert chime.
     */
    public void playGameStart() {
        playExclusive(() -> {
            try {
                final double[] melody = {330.0, 440.0, 550.0, 660.0};
                for (final var note : melody) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    final var periodNs = (long) (1_000_000_000.0 / note);
                    speaker.setPulse(periodNs, periodNs / 2);
                    Thread.sleep(90);
                    speaker.setPulse(1_000_000, 0);
                    Thread.sleep(20);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Plays the game over / city lost descending tone.
     */
    public void playGameOver() {
        playExclusive(() -> {
            try {
                final double[] melody = {400.0, 320.0, 240.0, 160.0};
                for (final var note : melody) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    final var periodNs = (long) (1_000_000_000.0 / note);
                    speaker.setPulse(periodNs, periodNs / 2);
                    Thread.sleep(180);
                    speaker.setPulse(1_000_000, 0);
                    Thread.sleep(30);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Immediately stops any playing sound and silences the speaker.
     */
    public void stop() {
        synchronized (this) {
            if (activeTask != null) {
                activeTask.cancel(true);
                activeTask = null;
            }
        }
        try {
            speaker.disable();
        } catch (final Exception ignored) {
        }
    }

    /**
     * Closes the manager and shuts down the underlying audio executor.
     */
    @Override
    public void close() {
        stop();
        executor.shutdownNow();
    }
}
