/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.spi_handle;
import org.periphery.spi_msg;

/**
 * Thread-safe SPI bus wrapper for Linux spidev devices using Foreign Function & Memory (FFM) API.
 * <p>
 * This class provides a high-performance interface to the C-periphery SPI library. It utilizes a persistent native {@link Arena}
 * and a dual-region {@link MemorySegment} to provide zero-allocation transfers during high-frequency operation.
 * </p>
 * <p>
 * All constructors require an explicit {@code bufferSize} to ensure native memory allocation aligns with application-specific
 * requirements, such as kernel buffer probing or large display data streams.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class SpiBus implements AutoCloseable {

    /**
     * Reentrant lock to ensure thread safety for SPI bus access.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Shared arena for managing the lifecycle of native memory segments.
     */
    private final Arena arena;

    /**
     * Native memory handle for the SPI device.
     */
    private final MemorySegment handle;

    /**
     * Pre-allocated native buffer for the {@code spi_msg} structure.
     */
    private final MemorySegment msgBuffer;

    /**
     * Pre-allocated dual-region native buffer for transmit and receive data. The TX region resides in the first half; the RX region
     * in the second half.
     */
    private final MemorySegment dataBuffer;

    /**
     * The maximum length of a single transfer supported by the pre-allocated buffer.
     */
    private final int maxSupportedLen;

    /**
     * Cached size of the {@code spi_msg} native structure layout.
     */
    private static final long MSG_SIZE = spi_msg.layout().byteSize();

    /**
     * Initialize SPI bus with basic settings and explicit buffer size.
     *
     * @param path The spidev device path (e.g., "/dev/spidev0.0").
     * @param mode The SPI mode (0, 1, 2, or 3).
     * @param maxSpeed The maximum clock frequency in Hertz.
     * @param bufferSize The maximum transfer length in bytes allowed for this instance.
     */
    public SpiBus(final String path, final int mode, final int maxSpeed, final int bufferSize) {
        this(path, mode, maxSpeed, 0, (byte) 8, (byte) 0, bufferSize);
    }

    /**
     * Initialize SPI bus with advanced settings and explicit buffer size.
     * <p>
     * Allocates a {@link MemorySegment} sized to {@code bufferSize * 2} to provide separate, non-overlapping regions for
     * full-duplex transmit and receive.
     * </p>
     *
     * @param path The spidev device path.
     * @param mode The SPI mode (0, 1, 2, or 3).
     * @param maxSpeed The maximum clock frequency in Hertz.
     * @param bitOrder The bit order (0 for MSB_FIRST, 1 for LSB_FIRST).
     * @param bitsPerWord The number of bits per word (typically 8).
     * @param extraFlags Additional SPI mode flags.
     * @param bufferSize The maximum transfer length in bytes allowed for this instance.
     * @throws RuntimeException If the SPI device cannot be opened.
     */
    public SpiBus(final String path, final int mode, final int maxSpeed,
            final int bitOrder, final byte bitsPerWord, final byte extraFlags,
            final int bufferSize) {
        this.arena = Arena.ofShared();
        this.handle = arena.allocate(spi_handle.layout());
        this.msgBuffer = arena.allocate(MSG_SIZE);
        this.maxSupportedLen = bufferSize;

        // Allocate space for both TX and RX buffers (Dual-Region)
        this.dataBuffer = arena.allocate((long) bufferSize * 2);

        final var nativePath = arena.allocateFrom(path);
        final var ret = Periphery.spi_open_advanced(handle, nativePath, mode,
                maxSpeed, bitOrder, bitsPerWord, extraFlags);

        if (ret < 0) {
            final var error = Periphery.spi_errmsg(handle).getString(0);
            throw new RuntimeException(String.format("Failed to open SPI %s: %s", path, error));
        }
        log.atDebug().log("SPI bus {} initialized (Mode: {}, Speed: {}, MaxBuf: {} bytes)",
                path, mode, maxSpeed, bufferSize);
    }

    /**
     * Performs a full-duplex SPI transfer using optimized native memory segments.
     *
     * @param tx The byte array containing data to be transmitted.
     * @param rx The byte array where received data will be stored.
     * @param len The number of bytes to transfer.
     * @return 0 on success, or -1 if the requested length exceeds the native buffer capacity.
     */
    public int transfer(final byte[] tx, final byte[] rx, final int len) {
        if (len > maxSupportedLen) {
            log.warn("Transfer length {} exceeds native buffer capacity {}", len, maxSupportedLen);
            return -1;
        }

        lock.lock();
        try {
            // RX segment offset starts at maxSupportedLen to ensure data isolation
            final var txSeg = dataBuffer.asSlice(0, len);
            final var rxSeg = dataBuffer.asSlice((long) maxSupportedLen, len);

            MemorySegment.copy(tx, 0, txSeg, ValueLayout.JAVA_BYTE, 0, len);

            spi_msg.txbuf(msgBuffer, txSeg);
            spi_msg.rxbuf(msgBuffer, rxSeg);
            spi_msg.len(msgBuffer, len);

            final var ret = Periphery.spi_transfer_advanced(handle, msgBuffer, 1);

            if (ret == 0) {
                MemorySegment.copy(rxSeg, ValueLayout.JAVA_BYTE, 0, rx, 0, len);
            }
            return ret;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves the current SPI mode from the hardware.
     *
     * @return The current SPI mode (0, 1, 2, or 3).
     */
    public int getMode() {
        lock.lock();
        try (final var local = Arena.ofConfined()) {
            final var modePtr = local.allocate(ValueLayout.JAVA_INT);
            Periphery.spi_get_mode(handle, modePtr);
            return modePtr.get(ValueLayout.JAVA_INT, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves the maximum clock frequency configured for the SPI bus.
     *
     * @return The clock frequency in Hertz.
     */
    public int getMaxSpeed() {
        lock.lock();
        try (final var local = Arena.ofConfined()) {
            final var speedPtr = local.allocate(ValueLayout.JAVA_INT);
            Periphery.spi_get_max_speed(handle, speedPtr);
            return speedPtr.get(ValueLayout.JAVA_INT, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a string representation of the SPI bus state.
     *
     * @return A formatted string from the native library.
     */
    @Override
    public String toString() {
        lock.lock();
        try (final var local = Arena.ofConfined()) {
            final var strBuf = local.allocate(256);
            Periphery.spi_tostring(handle, strBuf, strBuf.byteSize());
            return strBuf.getString(0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Safely closes the SPI device and releases all associated native memory.
     */
    @Override
    public void close() {
        lock.lock();
        try {
            try (arena) {
                if (handle.address() != 0) {
                    final var ret = Periphery.spi_close(handle);
                    if (ret < 0) {
                        log.atWarn().log("Native spi_close returned error code: {}", ret);
                    }
                }
            }
        } finally {
            lock.unlock();
            log.atDebug().log("SPI bus closed and native memory released.");
        }
    }
}
