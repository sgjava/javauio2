/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Magic Light Cup Module (KY-027) Composite Device Driver.
 * <p>
 * This class couples an input sensor component ({@link GpioSwitch}) and an output actuator component ({@link GpioOut}) co-located
 * on a single physical module. It leverages composition of existing periphery hardware classes to guarantee strict zero-allocation
 * execution paths, thread safety, and deterministic resource lifecycle management.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class MagicLightCup implements AutoCloseable {

    /**
     * Lock for thread-safe composite device operations.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Internal hardware tracking reference for the mercury tilt switch sensor.
     */
    private final GpioSwitch tiltSwitch;

    /**
     * Internal hardware tracking reference for the onboard illumination LED actuator.
     */
    private final GpioOut led;

    /**
     * Operational loop status state proxy for background threads.
     */
    private volatile boolean running;

    /**
     * Constructs a composite Magic Light Cup device driver using underlying framework components. No native loaders are executed
     * within this class scope.
     *
     * @param device GPIO character chip path (e.g., "/dev/gpiochip0").
     * @param switchLine GPIO line index mapped to the tilt sensor switch output (S).
     * @param ledLine GPIO line index mapped to the actuator LED input (L).
     * @throws RuntimeException If either underlying hardware component fails to open.
     */
    public MagicLightCup(final String device, final int switchLine, final int ledLine) {
        this.tiltSwitch = new GpioSwitch(device, switchLine);
        this.led = new GpioOut(device, ledLine);
        this.running = true;

        log.atDebug().log("Magic Light Cup composite driver initialized on {} [Switch Line: {}, LED Line: {}]",
                device, switchLine, ledLine);
    }

    /**
     * Reads the current raw digital state of the mercury tilt switch. Enforces a zero-allocation execution profile.
     *
     * @return {@code true} if the mercury switch circuit is closed/tripped (High); {@code false} otherwise (Low).
     */
    public boolean readSwitchState() {
        this.lock.lock();
        try {
            return this.tiltSwitch.getValue() == 1;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Directs the operational state of the onboard LED. Enforces a zero-allocation execution profile.
     *
     * @param state {@code true} to illuminate the LED; {@code false} to extinguish it.
     */
    public void setLedState(final boolean state) {
        this.lock.lock();
        try {
            this.led.setState(state);
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Attaches an asynchronous background monitoring thread to the underlying tilt sensor switch.
     *
     * @param pollIntervalMs Frequency to sample hardware (ms).
     * @param debounceMs Stability duration required to filter mechanical contact chatter (ms).
     * @param callback The consumer invoked with the validated primitive state (0 or 1).
     * @throws IllegalStateException If a monitoring watch boundary is already active on this instance.
     */
    public void watchSwitch(final long pollIntervalMs, final long debounceMs, final Consumer<Integer> callback) {
        this.lock.lock();
        try {
            this.tiltSwitch.watch(pollIntervalMs, debounceMs, callback);
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Assesses whether the device context is marked for active execution.
     *
     * @return {@code true} if the driver contexts are open; {@code false} otherwise.
     */
    public boolean isRunning() {
        return this.running;
    }

    /**
     * Manually overrides the runtime state flag to signal loop thread termination. Leveraged by containment shields to halt loops
     * before resources are dropped.
     */
    public void stop() {
        this.running = false;
    }

    /**
     * Performs a deterministic, sequential hardware-level close sequence.
     * <p>
     * Drops operational flags, extinguishes the local LED state to ground potential, and sequentially unmaps unmanaged native hooks
     * by closing composite device definitions.
     * </p>
     */
    @Override
    public void close() {
        this.lock.lock();
        try {
            this.stop();
            if (null != this.led) {
                this.led.off();
                this.led.close();
            }
            if (null != this.tiltSwitch) {
                this.tiltSwitch.close();
            }
            log.atDebug().log("Magic Light Cup composite driver handles released completely.");
        } finally {
            this.lock.unlock();
        }
    }
}
