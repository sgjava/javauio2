/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.sound;

import com.codeferm.periphery.device.PassiveSpeaker;
import java.util.Random;

/**
 * Explosion sound effect simulating white noise via rapid random frequency modulation.
 */
public final class ExplosionSound extends AbstractSoundEffect {

    private final Random random = new Random();

    public ExplosionSound(final PassiveSpeaker speaker) {
        super(speaker);
    }

    @Override
    protected void synthesize() throws InterruptedException {
        final var durationMs = 350L;
        final var startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < durationMs) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            final var freq = 60.0 + random.nextDouble() * 340.0;
            final var periodNs = (long) (1_000_000_000.0 / freq);
            speaker.setPulse(periodNs, periodNs / 2);
            Thread.sleep(12);
        }
    }
}
