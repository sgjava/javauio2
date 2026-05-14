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
import org.periphery.serial_handle;

/**
 * Thread-safe Serial (UART) wrapper for Linux termios tty devices using FFM.
 * <p>
 * Provides high-performance UART access with explicit memory management. Guarantees thread-safety via {@link ReentrantLock} and
 * utilizes a zero-allocation data buffer for I/O operations.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class Uart implements AutoCloseable {

    private final ReentrantLock lock = new ReentrantLock();
    private final Arena arena;
    private final MemorySegment handle;

    /**
     * Pre-allocated buffer for I/O and as a pointer target for property gets.
     */
    private final MemorySegment dataBuffer;
    private final int maxSupportedLen;

    /**
     * Initialize UART with explicit configuration.
     *
     * @param path Device path (e.g., "/dev/ttyS0").
     * @param baudrate Initial baud rate.
     * @param bufferSize Max size for native I/O operations.
     */
    public Uart(final String path, final int baudrate, final int bufferSize) {
        this.arena = Arena.ofShared();
        this.handle = arena.allocate(serial_handle.layout());
        this.maxSupportedLen = bufferSize;
        this.dataBuffer = arena.allocate(bufferSize);

        final var pathSeg = arena.allocateFrom(path);

        if (Periphery.serial_open(handle, pathSeg, baudrate) < 0) {
            final var error = Periphery.serial_errmsg(handle).getString(0);
            throw new RuntimeException("Failed to open %s: %s".formatted(path, error));
        }
        log.atDebug().log("UART {} opened: {} baud", path, baudrate);
    }

    /**
     * Reads data from the serial port into a Java byte array.
     *
     * @param buf Target Java byte array.
     * @param timeoutMs Timeout in ms (0: non-blocking, -1: blocking).
     * @return Bytes read, or -1 on error.
     */
    public int read(final byte[] buf, final int timeoutMs) {
        if (buf.length > maxSupportedLen) {
            log.error("Read size {} exceeds buffer {}", buf.length, maxSupportedLen);
            return -1;
        }
        lock.lock();
        try {
            final var ret = Periphery.serial_read(handle, dataBuffer, buf.length, timeoutMs);
            if (ret > 0) {
                MemorySegment.copy(dataBuffer, ValueLayout.JAVA_BYTE, 0, buf, 0, (int) ret);
            }
            return (int) ret;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes data from a Java byte array to the serial port.
     *
     * @param buf Data to transmit.
     * @return Bytes written, or -1 on error.
     */
    public int write(final byte[] buf) {
        if (buf.length > maxSupportedLen) {
            log.error("Write size {} exceeds buffer {}", buf.length, maxSupportedLen);
            return -1;
        }
        lock.lock();
        try {
            MemorySegment.copy(buf, 0, dataBuffer, ValueLayout.JAVA_BYTE, 0, buf.length);
            return (int) Periphery.serial_write(handle, dataBuffer, buf.length);
        } finally {
            lock.unlock();
        }
    }

    public void flush() {
        lock.lock();
        try {
            Periphery.serial_flush(handle);
        } finally {
            lock.unlock();
        }
    }

    /* --- Property Accessors using the pre-allocated dataBuffer as a pointer target --- */
    public int getBaudRate() {
        return getProperty(Periphery::serial_get_baudrate);
    }

    public void setBaudRate(final int baudRate) {
        lock.lock();
        try {
            Periphery.serial_set_baudrate(handle, baudRate);
        } finally {
            lock.unlock();
        }
    }

    public int getDataBits() {
        return getProperty(Periphery::serial_get_databits);
    }

    public void setDataBits(final int bits) {
        lock.lock();
        try {
            Periphery.serial_set_databits(handle, bits);
        } finally {
            lock.unlock();
        }
    }

    public int getParity() {
        return getProperty(Periphery::serial_get_parity);
    }

    public void setParity(final int parity) {
        lock.lock();
        try {
            Periphery.serial_set_parity(handle, parity);
        } finally {
            lock.unlock();
        }
    }

    public int getInputWaiting() {
        return getProperty(Periphery::serial_input_waiting);
    }

    /**
     * Helper to retrieve integer properties from the native layer.
     */
    private int getProperty(final java.util.function.BiFunction<MemorySegment, MemorySegment, Integer> getter) {
        lock.lock();
        try {
            getter.apply(handle, dataBuffer);
            return dataBuffer.get(ValueLayout.JAVA_INT, 0);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try (final var local = Arena.ofConfined()) {
            final var strBuf = local.allocate(256);
            Periphery.serial_tostring(handle, strBuf, strBuf.byteSize());
            return strBuf.getString(0);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (handle.address() != 0) {
                Periphery.serial_close(handle);
            }
            if (arena.scope().isAlive()) {
                arena.close();
            }
        } finally {
            lock.unlock();
        }
    }
}
