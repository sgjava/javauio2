/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.sound;

import com.codeferm.periphery.device.PassiveSpeaker;
import lombok.extern.slf4j.Slf4j;

/**
 * Standalone SID-style Voice Simulator for Single-Board Computers.
 * <p>
 * This class implements a software-defined oscillator with a 4-stage ADSR (Attack, Decay, Sustain, Release) envelope generator. It
 * mirrors the MOS 6581/8580 logic where the volume of a square wave is modulated by the duty cycle's active high duration.</p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class SidVoice {

    /**
     * Current state of the ADSR envelope machine.
     */
    public enum AdsrState {
        /**
         * No sound is being produced.
         */
        IDLE,
        /**
         * Volume rising to peak.
         */
        ATTACK,
        /**
         * Volume falling to sustain level.
         */
        DECAY,
        /**
         * Volume held constant.
         */
        SUSTAIN,
        /**
         * Volume falling to zero after gate release.
         */
        RELEASE
    }

    /**
     * The hardware speaker interface.
     */
    private final PassiveSpeaker speaker;
    /**
     * The update frequency in Hz.
     */
    private final long sampleRateHz;

    // Pre-calculated envelope increments/decrements to avoid division in tick()
    private double attackStep;
    private double decayStep;
    private double sustainLevel;
    private double releaseStep;

    // Internal synthesis state
    private AdsrState state = AdsrState.IDLE;
    private double amplitude = 0.0;
    private long periodNs = 0;
    private long lastDutyCycleNs = -1;

    /**
     * Constructs a SID voice tied to a specific hardware speaker.
     *
     * @param speaker The hardware-backed speaker wrapper.
     * @param sampleRateHz The internal update frequency (e.g., 1000 for 1ms resolution).
     */
    public SidVoice(final PassiveSpeaker speaker, final long sampleRateHz) {
        this.speaker = speaker;
        this.sampleRateHz = sampleRateHz;
    }

    /**
     * Configures the ADSR envelope values.
     *
     * @param attackSec Seconds to reach peak volume.
     * @param decaySec Seconds to reach sustain level.
     * @param sustain 0.0 to 1.0 sustain volume level.
     * @param releaseSec Seconds to return to zero after gate release.
     */
    public void setAdsr(final double attackSec, final double decaySec,
            final double sustain, final double releaseSec) {
        // Pre-calculate steps to save CPU cycles in the tick loop
        this.attackStep = 1.0 / (attackSec * this.sampleRateHz);
        this.decayStep = (1.0 - sustain) / (decaySec * this.sampleRateHz);
        this.sustainLevel = sustain;
        this.releaseStep = sustain / (releaseSec * this.sampleRateHz);
    }

    /**
     * Triggers the "Gate On" event. Resets the envelope to the Attack stage.
     *
     * @param frequency The note frequency in Hz.
     */
    public void gateOn(final double frequency) {
        this.periodNs = (long) (1_000_000_000L / frequency);
        this.state = AdsrState.ATTACK;
        // Debug logging is kept minimal to avoid timing jitter
        if (log.isDebugEnabled()) {
            log.debug("Gate ON: {} Hz", frequency);
        }
    }

    /**
     * Triggers the "Gate Off" event. Transitions the envelope to the Release stage.
     */
    public void gateOff() {
        this.state = AdsrState.RELEASE;
        if (log.isDebugEnabled()) {
            log.debug("Gate OFF");
        }
    }

    /**
     * Processes a single synthesis tick.
     * <p>
     * Updates the ADSR state machine and pushes new pulse width values to hardware only when a state change or amplitude shift
     * occurs to optimize FFM/JNI overhead.</p>
     */
    public void tick() {
        switch (this.state) {
            case ATTACK -> {
                this.amplitude += this.attackStep;
                if (this.amplitude >= 1.0) {
                    this.amplitude = 1.0;
                    this.state = AdsrState.DECAY;
                }
            }
            case DECAY -> {
                this.amplitude -= this.decayStep;
                if (this.amplitude <= this.sustainLevel) {
                    this.amplitude = this.sustainLevel;
                    this.state = AdsrState.SUSTAIN;
                }
            }
            case RELEASE -> {
                this.amplitude -= this.releaseStep;
                if (this.amplitude <= 0.0) {
                    this.amplitude = 0.0;
                    this.state = AdsrState.IDLE;
                }
            }
            case SUSTAIN ->
                this.amplitude = this.sustainLevel;
            case IDLE ->
                this.amplitude = 0.0;
            default ->
                throw new IllegalStateException("Unknown ADSR State");
        }

        this.updateHardware();
    }

    /**
     * Pushes calculated synthesis values to the physical PWM device. Uses a buffer-check to avoid redundant native calls.
     */
    private void updateHardware() {
        // 50% of period is the maximum amplitude for a square wave
        final var dutyCycleNs = (long) ((this.periodNs >> 1) * this.amplitude);

        // Optimization: Only hit the hardware if the pulse has actually changed
        if (dutyCycleNs != this.lastDutyCycleNs) {
            this.speaker.setPulse(this.periodNs, dutyCycleNs);
            this.lastDutyCycleNs = dutyCycleNs;
        }
    }

    /**
     * Checks if the voice is currently silent.
     *
     * @return True if the envelope is in the IDLE state.
     */
    public boolean isIdle() {
        return this.state == AdsrState.IDLE;
    }
}
