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
 * Thread-safe SPI bus wrapper for Linux spidev devices using FFM.
 * <p>
 * This class provides a high-performance interface to the C-periphery SPI library. It utilizes the shared native {@link Arena} from
 * {@link AbstractDevice} and a dual-region {@link MemorySegment} to provide zero-allocation transfers during high-frequency
 * operation.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class SpiBus extends AbstractDevice {

    private final ReentrantLock lock = new ReentrantLock();
    private final MemorySegment msgBuffer;
    private final MemorySegment dataBuffer;
    private final int maxSupportedLen;

    /**
     * Cached size of the spi_msg native structure layout.
     */
    private static final long MSG_SIZE = spi_msg.layout().byteSize();

    /**
     * Initialize SPI bus with basic settings.
     *
     * @param path The spidev device path (e.g., "/dev/spidev0.0").
     * @param mode The SPI mode (0, 1, 2, or 3).
     * @param maxSpeed The maximum clock frequency in Hertz.
     * @param bufferSize The maximum transfer length in bytes.
     */
    public SpiBus(final String path, final int mode, final int maxSpeed, final int bufferSize) {
        this(path, mode, maxSpeed, 0, (byte) 8, (byte) 0, bufferSize);
    }

    /**
     * Initialize SPI bus with advanced settings.
     *
     * @param path The spidev device path.
     * @param mode The SPI mode.
     * @param maxSpeed The maximum clock frequency.
     * @param bitOrder The bit order (0 for MSB_FIRST, 1 for LSB_FIRST).
     * @param bitsPerWord The number of bits per word (typically 8).
     * @param extraFlags Additional SPI mode flags.
     * @param bufferSize The maximum transfer length in bytes.
     */
    public SpiBus(final String path, final int mode, final int maxSpeed,
            final int bitOrder, final byte bitsPerWord, final byte extraFlags,
            final int bufferSize) {
        super(spi_handle.layout());

        this.maxSupportedLen = bufferSize;
        // Allocate space for both TX and RX buffers (Dual-Region) in inherited arena
        this.dataBuffer = getArena().allocate((long) bufferSize * 2);
        this.msgBuffer = getArena().allocate(MSG_SIZE);

        final var nativePath = getArena().allocateFrom(path);

        checkError(Periphery.spi_open_advanced(getHandle(), nativePath, mode,
                maxSpeed, bitOrder, bitsPerWord, extraFlags),
                "Failed to open SPI " + path);

        log.atDebug().log("SPI bus {} initialized (Mode: {}, Speed: {}, MaxBuf: {} bytes)",
                path, mode, maxSpeed, bufferSize);
    }

    /**
     * Performs a full-duplex SPI transfer using optimized native memory segments.
     *
     * @param tx Transmit byte array.
     * @param rx Receive byte array.
     * @param len Transfer length.
     * @return 0 on success, or -1 if length exceeds capacity.
     */
    public int transfer(final byte[] tx, final byte[] rx, final int len) {
        if (len > maxSupportedLen) {
            log.warn("Transfer length {} exceeds capacity {}", len, maxSupportedLen);
            return -1;
        }

        lock.lock();
        try {
            // TX at offset 0, RX at offset maxSupportedLen
            final var txSeg = dataBuffer.asSlice(0, len);
            final var rxSeg = dataBuffer.asSlice((long) maxSupportedLen, len);

            MemorySegment.copy(tx, 0, txSeg, ValueLayout.JAVA_BYTE, 0, len);

            spi_msg.txbuf(msgBuffer, txSeg);
            spi_msg.rxbuf(msgBuffer, rxSeg);
            spi_msg.len(msgBuffer, len);

            final var ret = Periphery.spi_transfer_advanced(getHandle(), msgBuffer, 1);

            if (ret == 0) {
                MemorySegment.copy(rxSeg, ValueLayout.JAVA_BYTE, 0, rx, 0, len);
            }
            return ret;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves current SPI mode.
     *
     * @return 0, 1, 2, or 3.
     */
    public int getMode() {
        lock.lock();
        try (final var local = Arena.ofConfined()) {
            final var modePtr = local.allocate(ValueLayout.JAVA_INT);
            Periphery.spi_get_mode(getHandle(), modePtr);
            return modePtr.get(ValueLayout.JAVA_INT, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves configured clock frequency.
     *
     * @return Hertz.
     */
    public int getMaxSpeed() {
        lock.lock();
        try (final var local = Arena.ofConfined()) {
            final var speedPtr = local.allocate(ValueLayout.JAVA_INT);
            Periphery.spi_get_max_speed(getHandle(), speedPtr);
            return speedPtr.get(ValueLayout.JAVA_INT, 0);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try (final var local = Arena.ofConfined()) {
            final var strBuf = local.allocate(256);
            Periphery.spi_tostring(getHandle(), strBuf, strBuf.byteSize());
            return strBuf.getString(0);
        } finally {
            lock.unlock();
        }
    }

    @Override
    protected void closeNative() {
        lock.lock();
        try {
            if (getHandle().address() != 0) {
                Periphery.spi_close(getHandle());
            }
        } finally {
            lock.unlock();
            log.atDebug().log("SPI bus native resources released.");
        }
    }
}
