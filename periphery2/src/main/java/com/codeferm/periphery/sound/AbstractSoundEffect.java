/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.sound;

import com.codeferm.periphery.device.PassiveSpeaker;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for monophonic sound effects and game tunes.
 * <p>
 * Encapsulates common execution patterns, safety controls, and interrupt handling for PWM-driven piezo speakers.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public abstract class AbstractSoundEffect implements Runnable {

    /**
     * The target passive speaker transport.
     */
    protected final PassiveSpeaker speaker;

    /**
     * Constructs a base sound effect.
     *
     * @param speaker The passive speaker instance.
     */
    protected AbstractSoundEffect(final PassiveSpeaker speaker) {
        this.speaker = speaker;
    }

    /**
     * Executes the sound synthesis logic. Subclasses implement specific frequency sweeps or melodies.
     *
     * @throws java.lang.InterruptedException possible exception.
     */
    protected abstract void synthesize() throws InterruptedException;

    @Override
    public final void run() {
        try {
            speaker.enable();
            synthesize();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Sound effect interrupted/preempted.");
        } catch (final Exception e) {
            log.error("Error executing sound effect: {}", e.getMessage());
        } finally {
            try {
                speaker.disable();
            } catch (final Exception ignored) {
                // Suppress on close/disable cleanup
            }
        }
    }
}
