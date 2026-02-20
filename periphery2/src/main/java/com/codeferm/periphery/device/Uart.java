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
 * This class provides high-performance access to UART hardware. It enforces explicit memory management by requiring the caller to
 * specify buffer sizes at construction, eliminating hidden defaults and ensuring predictable memory usage.
 * </p>
 * <p>
 * Implementation Details:
 * <ul>
 * <li>Utilizes a shared {@link Arena} for native memory lifecycle management.</li>
 * <li>Uses a pre-allocated {@link MemorySegment} as a data buffer and for C pointer arguments.</li>
 * <li>Thread-safety is guaranteed via {@link ReentrantLock} for all hardware I/O and configuration.</li>
 * </ul>
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class Uart implements AutoCloseable {

    /**
     * Reentrant lock for thread-safe access to serial hardware.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Arena for managing native memory lifecycle.
     */
    private final Arena arena;

    /**
     * Handle to the Serial device, sized by sizer.c during the build process.
     */
    private final MemorySegment handle;

    /**
     * Pre-allocated native data buffer for I/O and property pointers.
     */
    private final MemorySegment dataBuffer;

    /**
     * Maximum supported length for single I/O operations defined by the caller.
     */
    private final int maxSupportedLen;

    /**
     * Initialize UART with explicit configuration.
     *
     * @param path Device path (e.g., "/dev/ttyS0").
     * @param baudrate Initial baud rate for the connection.
     * @param bufferSize Maximum size (in bytes) for native I/O operations and property retrieval.
     * @throws RuntimeException If the native periphery library fails to open the device.
     */
    public Uart(final String path, final int baudrate, final int bufferSize) {
        this.arena = Arena.ofShared();
        this.handle = arena.allocate(serial_handle.layout());
        this.maxSupportedLen = bufferSize;
        this.dataBuffer = arena.allocate(bufferSize);

        final var pathSeg = arena.allocateFrom(path);
        // periphery serial_open defaults to 8N1; use setters to override after init.
        if (Periphery.serial_open(handle, pathSeg, baudrate) < 0) {
            final var error = Periphery.serial_errmsg(handle).getString(0);
            throw new RuntimeException("Failed to open %s: %s".formatted(path, error));
        }
        log.atDebug().log("UART {} opened: {} baud, {} byte buffer", path, baudrate, bufferSize);
    }

    /**
     * Reads data from the serial port into the provided Java byte array.
     *
     * @param buf Target Java byte array to store incoming data.
     * @param timeoutMs Timeout in milliseconds (0 for non-blocking, -1 for blocking).
     * @return Number of bytes actually read, or -1 if the request exceeds buffer capacity.
     */
    public int read(final byte[] buf, final int timeoutMs) {
        if (buf.length > maxSupportedLen) {
            log.error("Read size {} exceeds native buffer capacity {}", buf.length, maxSupportedLen);
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
     * Writes data from the provided Java byte array to the serial port.
     *
     * @param buf Data to be transmitted.
     * @return Number of bytes actually written, or -1 if the request exceeds buffer capacity.
     */
    public int write(final byte[] buf) {
        if (buf.length > maxSupportedLen) {
            log.error("Write size {} exceeds native buffer capacity {}", buf.length, maxSupportedLen);
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

    /**
     * Flushes the serial transmit and receive buffers.
     */
    public void flush() {
        lock.lock();
        try {
            Periphery.serial_flush(handle);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves the current baud rate.
     *
     * @return Current baud rate.
     */
    public int getBaudRate() {
        lock.lock();
        try {
            Periphery.serial_get_baudrate(handle, dataBuffer);
            return dataBuffer.get(ValueLayout.JAVA_INT, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Updates the connection baud rate.
     *
     * @param baudRate Desired baud rate.
     */
    public void setBaudRate(final int baudRate) {
        lock.lock();
        try {
            Periphery.serial_set_baudrate(handle, baudRate);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves the number of data bits configured.
     *
     * @return Number of bits (5, 6, 7, or 8).
     */
    public int getDataBits() {
        lock.lock();
        try {
            Periphery.serial_get_databits(handle, dataBuffer);
            return dataBuffer.get(ValueLayout.JAVA_INT, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the number of data bits.
     *
     * @param bits Data bits per character.
     */
    public void setDataBits(final int bits) {
        lock.lock();
        try {
            Periphery.serial_set_databits(handle, bits);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves the parity setting.
     *
     * @return Parity mode (0: None, 1: Odd, 2: Even).
     */
    public int getParity() {
        lock.lock();
        try {
            Periphery.serial_get_parity(handle, dataBuffer);
            return dataBuffer.get(ValueLayout.JAVA_INT, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the parity bit configuration.
     *
     * @param parity Parity mode (0: None, 1: Odd, 2: Even).
     */
    public void setParity(final int parity) {
        lock.lock();
        try {
            Periphery.serial_set_parity(handle, parity);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves the number of stop bits.
     *
     * @return Stop bits (1 or 2).
     */
    public int getStopBits() {
        lock.lock();
        try {
            Periphery.serial_get_stopbits(handle, dataBuffer);
            return dataBuffer.get(ValueLayout.JAVA_INT, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the number of stop bits.
     *
     * @param stopbits Number of stop bits (1 or 2).
     */
    public void setStopBits(final int stopbits) {
        lock.lock();
        try {
            Periphery.serial_set_stopbits(handle, stopbits);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of bytes available in the input queue.
     *
     * @return Bytes available for reading.
     */
    public int getInputWaiting() {
        lock.lock();
        try {
            Periphery.serial_input_waiting(handle, dataBuffer);
            return dataBuffer.get(ValueLayout.JAVA_INT, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a string representation of the native UART state.
     *
     * @return Native handle state description.
     */
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

    /**
     * Closes the serial device and releases associated native memory segments.
     */
    @Override
    public void close() {
        lock.lock();
        try {
            try (arena) {
                if (handle.address() != 0) {
                    Periphery.serial_close(handle);
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
