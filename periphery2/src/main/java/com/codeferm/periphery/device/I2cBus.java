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
 * Thread-safe I2C bus wrapper for Linux i2c-dev character devices using FFM.
 * <p>
 * This class provides thread-safe access to an I2C bus by locking during transactions. It utilizes hardware-accurate layouts
 * generated during the build process to eliminate memory size guessing.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class I2cBus implements AutoCloseable {

    /**
     * Reentrant lock for thread-safe I2C access.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Arena for managing native memory lifecycle.
     */
    private final Arena arena;

    /**
     * Handle to the I2C device. Size is determined by sizer.c during build.
     */
    private final MemorySegment handle;

    /**
     * Pre-allocated buffer for i2c_msg structures (2 messages).
     */
    private final MemorySegment msgBuffer;

    /**
     * Pre-allocated data buffer for I/O operations.
     */
    private final MemorySegment dataBuffer;

    /**
     * Cached struct size for i2c_msg.
     */
    private static final long MSG_SIZE = i2c_msg.layout().byteSize();

    /**
     * Linux I2C Read flag.
     */
    private static final short I2C_M_RD = 0x0001;

    /**
     * Initialize I2C bus.
     *
     * @param device I2C device path (e.g., "/dev/i2c-1").
     */
    public I2cBus(final String device) {
        this.arena = Arena.ofShared();
        // Use the generated layout - no guessing!
        this.handle = arena.allocate(i2c_handle.layout());
        this.msgBuffer = arena.allocate(MSG_SIZE * 2);
        this.dataBuffer = arena.allocate(256);

        final var path = arena.allocateFrom(device);
        if (Periphery.i2c_open(handle, path) < 0) {
            // Using getString(0) for null-terminated strings in modern FFM
            final var error = Periphery.i2c_errmsg(handle).getString(0);
            throw new RuntimeException(String.format("Failed to open %s: %s", device, error));
        }
        log.atDebug().log("I2C bus {} initialized", device);
    }

    /**
     * Helper to set i2c_msg fields.
     *
     * @param msgIdx Index in msgBuffer.
     * @param addr Device address.
     * @param flags I2C flags.
     * @param buf Native data buffer.
     * @param len Length of transfer.
     */
    private void setMsg(final int msgIdx, final short addr, final short flags,
            final MemorySegment buf, final int len) {
        final var msg = msgBuffer.asSlice(msgIdx * MSG_SIZE, MSG_SIZE);
        i2c_msg.addr(msg, addr);
        i2c_msg.flags(msg, flags);
        i2c_msg.len(msg, (short) len);
        i2c_msg.buf(msg, buf);
    }

    /**
     * Read from 8-bit register into byte array.
     *
     * @param addr Peripheral address.
     * @param reg Register address.
     * @param buf Target buffer.
     * @return 0 on success.
     */
    public int readReg8(final short addr, final short reg, final byte[] buf) {
        lock.lock();
        try {
            dataBuffer.set(ValueLayout.JAVA_BYTE, 0, (byte) reg);
            setMsg(0, addr, (short) 0, dataBuffer, 1);
            setMsg(1, addr, I2C_M_RD, dataBuffer.asSlice(1, buf.length), buf.length);

            final var ret = Periphery.i2c_transfer(handle, msgBuffer, 2);
            if (ret == 0) {
                MemorySegment.copy(dataBuffer, ValueLayout.JAVA_BYTE, 1, buf, 0, buf.length);
            }
            return ret;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Write value to 8-bit register.
     *
     * @param addr Peripheral address.
     * @param reg Register address.
     * @param value Value.
     * @return 0 on success.
     */
    public int writeReg8(final short addr, final short reg, final short value) {
        lock.lock();
        try {
            dataBuffer.set(ValueLayout.JAVA_BYTE, 0, (byte) reg);
            dataBuffer.set(ValueLayout.JAVA_BYTE, 1, (byte) value);
            setMsg(0, addr, (short) 0, dataBuffer, 2);
            return Periphery.i2c_transfer(handle, msgBuffer, 1);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get string representation of the native I2C state.
     *
     * @return Handle description string.
     */
    @Override
    public String toString() {
        lock.lock();
        try (final var localArena = Arena.ofConfined()) {
            final var strBuf = localArena.allocate(128);
            Periphery.i2c_tostring(handle, strBuf, strBuf.byteSize());
            return strBuf.getString(0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Close native handle and arena.
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
