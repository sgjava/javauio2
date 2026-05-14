/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * ADXL345 3-Axis digital accelerometer device class using I2cBus.
 * <p>
 * This class provides a high-level interface to the ADXL345 accelerometer. It delegates low-level I2C operations to a borrowed,
 * thread-safe {@link I2cBus}.
 * </p>
 * <p>
 * Zero-allocation in the hot path, full Javadoc, and strict use of {@code final} and {@code var}.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class Adxl345 implements AutoCloseable {

    /**
     * Borrowed thread-safe I2C bus. Lifecycle is managed by the caller.
     */
    private final I2cBus i2cBus;

    /**
     * I2C address of the ADXL345 device.
     */
    private final short address;

    /**
     * Scaling factor used to convert raw register values to G-force.
     */
    private float scalingFactor;

    /**
     * Earth gravity constant in m/s^2.
     */
    private static final float G_TO_MS2 = 9.80665f;

    /**
     * Reusable buffer for 8-bit register reads to avoid per-call allocation.
     */
    private final byte[] regBuffer = new byte[1];

    /**
     * Reusable buffer for axis data (6 bytes) to avoid per-read allocation.
     */
    private final byte[] axisBuffer = new byte[6];

    /**
     * Initialize ADXL345 with an existing I2C bus and device address.
     *
     * @param i2cBus The thread-safe I2C bus to use.
     * @param address I2C address of the ADXL345.
     */
    public Adxl345(final I2cBus i2cBus, final short address) {
        this.i2cBus = i2cBus;
        this.address = address;
        log.atDebug().log("ADXL345 initialized on bus at 0x{}", Integer.toHexString(address));
    }

    /**
     * Writes an 8-bit value to a device register.
     *
     * @param reg Register address.
     * @param value Value to write.
     */
    public void writeReg8(final short reg, final short value) {
        if (i2cBus.writeReg8(address, reg, (byte) (value & 0xff)) < 0) {
            log.error("I2C write failed for register 0x{}", Integer.toHexString(reg));
        }
    }

    /**
     * Reads an 8-bit value from a device register using the pre-allocated buffer.
     *
     * @param reg Register address.
     * @return The 8-bit value read.
     */
    public short readReg8(final short reg) {
        if (i2cBus.readReg8(address, reg, regBuffer) < 0) {
            log.error("I2C read failed for register 0x{}", Integer.toHexString(reg));
        }
        return (short) (regBuffer[0] & 0xff);
    }

    /**
     * Enable the device and set default configuration.
     */
    public void enable() {
        writeReg8((short) 0x2d, (short) 0x08); // POWER_CTL: Measure mode
        setRange((short) 0x00);                // +/- 2g
        setDataRate((short) 0x0a);             // 100 Hz
        refreshScalingFactor();
    }

    /**
     * Sets the measurement range in the DATA_FORMAT register.
     *
     * @param value Range value (0x00: 2g, 0x01: 4g, 0x02: 8g, 0x03: 16g).
     */
    public void setRange(final short value) {
        final var current = readReg8((short) 0x31);
        final var newValue = (short) (((current & ~0x0f) | value) | 0x08);
        writeReg8((short) 0x31, newValue);
        refreshScalingFactor();
    }

    /**
     * Sets the data rate in the BW_RATE register.
     *
     * @param value Rate value (default 0x0a is 100Hz).
     */
    public void setDataRate(final short value) {
        writeReg8((short) 0x2c, (short) (value & 0x0f));
    }

    /**
     * Updates internal scaling factor based on current resolution settings.
     */
    public void refreshScalingFactor() {
        final var format = readReg8((short) 0x31);
        // Bit 3 is FULL_RES
        if ((format & 0x08) == 0x08) {
            this.scalingFactor = 0.004f;
        } else {
            final var range = (short) (format & 0x03);
            final var gRange = 4.0f * (float) Math.pow(2, range);
            this.scalingFactor = gRange / 1024.0f;
        }
    }

    /**
     * Reads x, y, z axes and returns scaled acceleration in m/s^2.
     * <p>
     * Performs a single multi-byte I2C read into the pre-allocated axis buffer.
     * </p>
     *
     * @return Map containing "x", "y", and "z" float values.
     */
    public Map<String, Float> read() {
        // Read 6 bytes starting from DATAX0 (0x32)
        if (i2cBus.readReg8(address, (short) 0x32, axisBuffer) < 0) {
            log.error("I2C read axes failed");
        }

        final var x = (short) (((axisBuffer[1] & 0xff) << 8) | (axisBuffer[0] & 0xff));
        final var y = (short) (((axisBuffer[3] & 0xff) << 8) | (axisBuffer[2] & 0xff));
        final var z = (short) (((axisBuffer[5] & 0xff) << 8) | (axisBuffer[4] & 0xff));

        final var map = new HashMap<String, Float>(3);
        final var factor = scalingFactor * G_TO_MS2;
        map.put("x", x * factor);
        map.put("y", y * factor);
        map.put("z", z * factor);
        return map;
    }

    /**
     * Reads the device ID from the DEVID register.
     *
     * @return The device ID (should be 0xE5).
     */
    public short getDeviceId() {
        return readReg8((short) 0x00);
    }

    /**
     * Closes the ADXL345 device state.
     * <p>
     * Puts the hardware into standby mode (POWER_CTL bit 3 = 0) to minimize power consumption and bus traffic. Does not close the
     * shared I2C bus.
     * </p>
     */
    @Override
    public void close() {
        // Puts chip in standby while bus is still active
        writeReg8((short) 0x2d, (short) 0x00);
        log.atDebug().log("ADXL345 at 0x{} placed in standby mode", Integer.toHexString(address));
    }
}
