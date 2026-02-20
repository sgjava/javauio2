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
import org.periphery.i2c_handle;
import org.periphery.i2c_msg;

/**
 * Thread-safe I2C bus wrapper for Linux i2c-dev devices using FFM.
 * <p>
 * This class provides high-performance access to I2C hardware. It enforces explicit 
 * memory management by requiring the caller to specify the buffer size at construction, 
 * ensuring predictable memory usage for native allocations.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class I2cBus implements AutoCloseable {

    /**
     * Reentrant lock for thread-safe access to the I2C hardware.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Arena for managing native memory lifecycle.
     */
    private final Arena arena;

    /**
     * Handle to the I2C device, sized by the sizer during the build process.
     */
    private final MemorySegment handle;

    /**
     * Pre-allocated native data buffer for I/O and message structures.
     */
    private final MemorySegment dataBuffer;

    /**
     * Pre-allocated array of two i2c_msg structures for combined transactions (Write/Read).
     */
    private final MemorySegment msgs;

    /**
     * Maximum supported length for single I/O operations defined by the caller.
     */
    private final int maxSupportedLen;

    /**
     * Initialize I2C bus with explicit configuration.
     *
     * @param device     I2C device path (e.g., "/dev/i2c-1").
     * @param bufferSize Maximum size (in bytes) for native I/O operations.
     * @throws RuntimeException If the native periphery library fails to open the device.
     */
    public I2cBus(final String device, final int bufferSize) {
        this.arena = Arena.ofShared();
        this.handle = arena.allocate(i2c_handle.layout());
        this.maxSupportedLen = bufferSize;
        this.dataBuffer = arena.allocate(bufferSize);
        // Allocate space for 2 i2c_msg structs for combined Write+Read transfers
        this.msgs = arena.allocate(i2c_msg.layout().byteSize() * 2);

        final var deviceSeg = arena.allocateFrom(device);
        if (Periphery.i2c_open(handle, deviceSeg) < 0) {
            final var error = Periphery.i2c_errmsg(handle).getString(0);
            throw new RuntimeException("Failed to open %s: %s".formatted(device, error));
        }
        log.atDebug().log("I2C bus {} opened with {} byte buffer", device, bufferSize);
    }

    /**
     * Writes an 8-bit value to a device register.
     *
     * @param address I2C device address.
     * @param reg     Register address.
     * @param value   Value to write.
     * @return 0 on success, or negative error code.
     */
    public int writeReg8(final short address, final short reg, final byte value) {
        lock.lock();
        try {
            // Write 2 bytes: [Register, Value]
            dataBuffer.set(ValueLayout.JAVA_BYTE, 0, (byte) reg);
            dataBuffer.set(ValueLayout.JAVA_BYTE, 1, value);

            // Configure single write message
            final var msg = msgs.asSlice(0, i2c_msg.layout().byteSize());
            i2c_msg.addr(msg, address);
            i2c_msg.flags(msg, (short) 0);
            i2c_msg.len(msg, (short) 2);
            i2c_msg.buf(msg, dataBuffer);

            return Periphery.i2c_transfer(handle, msgs, 1);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads multiple bytes from a device register using a combined transfer.
     *
     * @param address I2C device address.
     * @param reg     Register address.
     * @param buf     Target Java byte array.
     * @return 0 on success, or negative error code.
     */
    public int readReg8(final short address, final short reg, final byte[] buf) {
        if (buf.length > maxSupportedLen) {
            log.error("Read size {} exceeds native buffer capacity {}", buf.length, maxSupportedLen);
            return -1;
        }
        lock.lock();
        try {
            // Message 1: Write Register Address
            dataBuffer.set(ValueLayout.JAVA_BYTE, 0, (byte) reg);
            final var msgWrite = msgs.asSlice(0, i2c_msg.layout().byteSize());
            i2c_msg.addr(msgWrite, address);
            i2c_msg.flags(msgWrite, (short) 0);
            i2c_msg.len(msgWrite, (short) 1);
            i2c_msg.buf(msgWrite, dataBuffer);

            // Message 2: Read Data (using dataBuffer offset to avoid collision)
            final var msgRead = msgs.asSlice(i2c_msg.layout().byteSize(), i2c_msg.layout().byteSize());
            i2c_msg.addr(msgRead, address);
            i2c_msg.flags(msgRead, (short) 0x0001); // I2C_M_RD
            i2c_msg.len(msgRead, (short) buf.length);
            i2c_msg.buf(msgRead, dataBuffer.asSlice(1));

            final var ret = Periphery.i2c_transfer(handle, msgs, 2);
            if (ret >= 0) {
                MemorySegment.copy(dataBuffer, ValueLayout.JAVA_BYTE, 1, buf, 0, buf.length);
            }
            return ret;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a string representation of the I2C bus state.
     *
     * @return Native handle state description.
     */
    @Override
    public String toString() {
        lock.lock();
        try (final var local = Arena.ofConfined()) {
            final var strBuf = local.allocate(256);
            Periphery.i2c_tostring(handle, strBuf, strBuf.byteSize());
            return strBuf.getString(0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes the I2C bus and releases native memory segments.
     */
    @Override
    public void close() {
        lock.lock();
        try {
            try (arena) {
                if (handle.address() != 0) {
                    Periphery.i2c_close(handle);
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
