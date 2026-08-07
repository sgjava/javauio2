/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

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
public final class Uart extends AbstractDevice {

    /**
     * Reentrant lock for thread-safe hardware access.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Pre-allocated buffer for I/O and as a pointer target for property gets.
     */
    private final MemorySegment dataBuffer;

    /**
     * Pre-allocated buffer for the toString representation to avoid execution-time allocations.
     */
    private final MemorySegment toStringBuffer;

    /**
     * Maximum length supported for native I/O buffer interactions.
     */
    private final int maxSupportedLen;

    /**
     * Initialize UART with explicit configuration.
     *
     * @param path Device path (e.g., "/dev/ttyS0").
     * @param baudrate Initial baud rate.
     * @param bufferSize Max size for native I/O operations.
     * @throws RuntimeException If the serial device cannot be opened.
     */
    public Uart(final String path, final int baudrate, final int bufferSize) {
        // Pass the jextract layout up to the base layout constructor
        super(serial_handle.layout());

        final var deviceArena = getArena();
        final var deviceHandle = getHandle();

        this.maxSupportedLen = bufferSize;
        this.dataBuffer = deviceArena.allocate(bufferSize);
        // Optimization: Pre-allocate static heap bounds to drop execution thrashing to absolute zero
        this.toStringBuffer = deviceArena.allocate(256);

        final var pathSeg = deviceArena.allocateFrom(path);

        lock.lock();
        try {
            if (Periphery.serial_open(deviceHandle, pathSeg, baudrate) < 0) {
                final var error = Periphery.serial_errmsg(deviceHandle).getString(0);
                throw new RuntimeException("Failed to open %s: %s".formatted(path, error));
            }
        } finally {
            lock.unlock();
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
            final var ret = Periphery.serial_read(getHandle(), dataBuffer, buf.length, timeoutMs);
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
            return (int) Periphery.serial_write(getHandle(), dataBuffer, buf.length);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Flushes the serial device transmission buffers.
     */
    public void flush() {
        lock.lock();
        try {
            Periphery.serial_flush(getHandle());
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
            Periphery.serial_set_baudrate(getHandle(), baudRate);
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
            Periphery.serial_set_databits(getHandle(), bits);
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
            Periphery.serial_set_parity(getHandle(), parity);
        } finally {
            lock.unlock();
        }
    }

    public int getInputWaiting() {
        return getProperty(Periphery::serial_input_waiting);
    }

    /**
     * Helper to retrieve integer properties from the native layer.
     *
     * @param getter Native library reference method mapping.
     * @return Parsed property code.
     */
    private int getProperty(final java.util.function.BiFunction<MemorySegment, MemorySegment, Integer> getter) {
        lock.lock();
        try {
            getter.apply(getHandle(), dataBuffer);
            return dataBuffer.get(ValueLayout.JAVA_INT, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a string representation of the UART properties.
     *
     * @return Serial port state structured string.
     */
    @Override
    public String toString() {
        lock.lock();
        try {
            Periphery.serial_tostring(getHandle(), toStringBuffer, (int) toStringBuffer.byteSize());
            return toStringBuffer.getString(0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Releases the native unmanaged c-periphery UART handle resources.
     * <p>
     * This method is automatically called inside the final `close()` block of {@link AbstractDevice} before the arena boundary is
     * discarded.
     * </p>
     */
    @Override
    protected void closeNative() {
        lock.lock();
        try {
            final var deviceHandle = getHandle();
            if (getArena().scope().isAlive() && deviceHandle != null && deviceHandle.address() != 0) {
                Periphery.serial_close(deviceHandle);
                log.atDebug().log("Native UART resources closed cleanly.");
            }
        } finally {
            lock.unlock();
        }
    }
}
