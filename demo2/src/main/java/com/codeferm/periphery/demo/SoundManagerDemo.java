/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.device.PassiveSpeaker;
import com.codeferm.periphery.device.PwmDeviceFactory;
import com.codeferm.periphery.sound.SoundManager;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Sound Manager and Effects Demo.
 * <p>
 * Demonstrates monophonic audio management using {@link SoundManager}. Exercises game sound effects (fire, explosions, start/game
 * over tunes) and background music loops without blocking execution threads.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "SoundManagerDemo",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "Demonstrates sound effects and background music using SoundManager.")
public final class SoundManagerDemo extends AbstractDemo {

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
     * Executes the sound demonstration sequence.
     * <p>
     * Initializes the PWM transport, wraps it in a {@link PassiveSpeaker} and {@link SoundManager}, then triggers various game
     * audio events with appropriate delays to simulate a live gaming session.
     * </p>
     *
     * @return Exit code (0 for success, 1 for error).
     * @throws Exception On hardware or execution error.
     */
    @Override
    public Integer call() throws Exception {
        addTerminalHook();
        log.info("Starting Sound Manager Demo [Mode: {}, Device: {}, Channel: {}]", mode, device, channel);

        // Single Ownership. Speaker manages transport; SoundManager manages async audio tasks.
        try (
                final var speaker = new PassiveSpeaker(PwmDeviceFactory.create(mode, device, channel)); final var soundManager
                = new SoundManager(speaker)) {
            // Play game start tune
            log.info("Playing Game Start tune...");
            soundManager.playGameStart();
            Thread.sleep(2000);

            // Simulate background action loop with sound effects
            log.info("Simulating game action (Fire and Explosions)...");
            for (var i = 1; i <= 3; i++) {
                log.info("Action round {}", i);
                soundManager.playFire();
                Thread.sleep(600);
                soundManager.playFire();
                Thread.sleep(600);
                soundManager.playExplosion();
                Thread.sleep(1200);
            }

            // Play game over tune
            log.info("Playing Game Over tune...");
            soundManager.playGameOver();
            Thread.sleep(2500);

            log.info("Sound Manager Demo complete.");
            return 0;
        } catch (final Exception e) {
            log.error("Sound Manager Demo failure: {}", e.getMessage());
            return 1;
        }
    }

    /**
     * CLI entry point for the Sound Manager Demo.
     *
     * @param args Command line arguments passed to picocli.
     */
    public static void main(final String[] args) {
        System.exit(new CommandLine(new SoundManagerDemo()).execute(args));
    }
}
