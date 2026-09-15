/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.sound;

import com.codeferm.periphery.device.PassiveSpeaker;

/**
 * Laser shot sound effect utilizing a rapid high-to-low frequency sweep.
 */
public final class LaserSound extends AbstractSoundEffect {

    public LaserSound(final PassiveSpeaker speaker) {
        super(speaker);
    }

    @Override
    protected void synthesize() throws InterruptedException {
        // Sweep down quickly from 880 Hz to 220 Hz
        for (var freq = 880.0; freq > 220.0; freq -= 40.0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            final var periodNs = (long) (1_000_000_000.0 / freq);
            speaker.setPulse(periodNs, periodNs / 2);
            Thread.sleep(15);
        }
    }
}
