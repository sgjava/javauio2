/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;

/**
 * Microphone sound sensor (Digital Output) with acoustic pulse validation using Java FFM.
 * <p>
 * This class is designed for 37-in-1 microphone modules utilizing an LM393 comparator circuit. Hardware integration requires
 * connecting to the digital output pin <b>(D0)</b> rather than the analog output pin <b>(A0)</b>. The onboard multi-turn
 * potentiometer screw must be meticulously dialed into the ambient noise floor: turn clockwise to increase sensitivity until the
 * data LED illuminates, then back off counter-clockwise slightly until the LED just extinguishes during silence.
 * </p>
 * <p>
 * The software architecture optimizes for low-latency acoustic edge transitions. It triggers immediately upon detecting sound and
 * applies a trailing lockout window to cleanly mask subsequent mechanical or acoustic reflections (comparator chatter) without
 * dropping events.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class SoundSensor implements AutoCloseable {

    /**
     * GPIO direction input constant.
     */
    private static final int GPIO_DIR_IN = 0;

    /**
     * Reentrant lock for thread-safe access to native handles and buffers.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Managed arena for native memory segments.
     */
    private final Arena arena;

    /**
     * Native handle for the GPIO character device.
     */
    private final MemorySegment handle;

    /**
     * Pre-allocated buffer for state reads to ensure zero-allocation polling.
     */
    private final MemorySegment stateBuffer;

    /**
     * Flag to control the lifecycle of the background monitoring thread.
     */
    private volatile boolean watching = false;

    /**
     * Initializes the sound sensor by allocating native memory segments and opening the specified GPIO line.
     *
     * @param device GPIO device path (e.g., "/dev/gpiochip0").
     * @param line GPIO line number (e.g., 17 for Raspberry Pi).
     * @throws RuntimeException If the native GPIO device cannot be opened.
     */
    public SoundSensor(final String device, final int line) {
        this.arena = Arena.ofShared();
        this.handle = this.arena.allocate(gpio_handle.layout());
        this.stateBuffer = this.arena.allocate(ValueLayout.JAVA_BOOLEAN);
        final var cDevice = this.arena.allocateFrom(device);
        if (Periphery.gpio_open(this.handle, cDevice, line, GPIO_DIR_IN) < 0) {
            final var errorMsg = Periphery.gpio_errmsg(this.handle).getString(0);
            throw new RuntimeException(String.format("Failed to open native GPIO %s Line %d: %s",
                    device, line, errorMsg));
        }
        log.atDebug().log("Sound Sensor initialized on {} line {}", device, line);
    }

    /**
     * Synchronously reads the current digital state of the comparator pin.
     *
     * @return 1 for sound detected (High), 0 for quiet (Low).
     */
    public int getValue() {
        this.lock.lock();
        try {
            Periphery.gpio_read(this.handle, this.stateBuffer);
            return this.stateBuffer.get(ValueLayout.JAVA_BOOLEAN, 0) ? 1 : 0;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Monitors for sound events using a low-latency instant-trigger and trailing lockout window.
     * <p>
     * Spawns a high-performance daemon thread that polls the native state buffer. When a high edge is captured, the callback fires
     * instantly. Subsequent fluctuations are discarded until the lockout window expires and a stable quiet threshold is restored.
     * </p>
     *
     * @param pollIntervalMs Sampling rate in milliseconds.
     * @param lockoutMs Trailing quiet duration required before accepting the next sound event.
     * @param callback The consumer invoked with the validated edge state (0 or 1).
     * @throws IllegalStateException If the background monitoring thread is already running.
     */
    public void watch(final long pollIntervalMs, final long lockoutMs, final Consumer<Integer> callback) {
        if (this.watching) {
            throw new IllegalStateException("Watch already active");
        }
        this.watching = true;

        final var thread = new Thread(() -> {
            log.info("Acoustic watch thread started: {}ms poll, {}ms trailing lockout.", pollIntervalMs, lockoutMs);
            var lastValidValue = 0;
            var lockOutUntil = 0L;
            while (this.watching && !Thread.currentThread().isInterrupted()) {
                final var currentValue = this.getValue();
                final var currentTime = System.nanoTime();
                if (currentValue == 1 && lastValidValue == 0) {
                    if (currentTime > lockOutUntil) {
                        lastValidValue = 1;
                        callback.accept(1);
                        lockOutUntil = currentTime + TimeUnit.MILLISECONDS.toNanos(lockoutMs);
                    }
                } else if (currentValue == 0 && lastValidValue == 1) {
                    if (currentTime > lockOutUntil) {
                        lastValidValue = 0;
                        callback.accept(0);
                    }
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(pollIntervalMs);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            log.info("Acoustic watch thread stopping.");
        }, "SoundWatch-Thread");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Stops background monitoring and safely closes the native character device and memory arena.
     */
    @Override
    public void close() {
        this.watching = false;
        this.lock.lock();
        try {
            try (this.arena) {
                if (this.handle.address() != 0) {
                    Periphery.gpio_close(this.handle);
                }
            }
        } finally {
            this.lock.unlock();
        }
    }
}
