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
 * This class provides a high-level interface to the ADXL345 accelerometer. It delegates low-level I2C operations to the thread-safe
 * {@link I2cBus} to ensure hardware-accurate and synchronized access.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0-SNAPSHOT
 * @since 1.0.0
 */
@Slf4j
public class Adxl345 implements AutoCloseable {

    /**
     * Thread-safe I2C bus.
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
     * Reusable buffer for axis data to avoid per-read allocation.
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
        if (i2cBus.writeReg8(address, reg, value) < 0) {
            log.error("I2C write failed for register 0x{}", Integer.toHexString(reg));
        }
    }

    /**
     * Reads an 8-bit value from a device register.
     *
     * @param reg Register address.
     * @return The 8-bit value read.
     */
    public short readReg8(final short reg) {
        final var buf = new byte[1];
        if (i2cBus.readReg8(address, reg, buf) < 0) {
            log.error("I2C read failed for register 0x{}", Integer.toHexString(reg));
        }
        return (short) (buf[0] & 0xff);
    }

    /**
     * Enable the device and set default configuration.
     */
    public void enable() {
        writeReg8((short) 0x2d, (short) 0x08); // POWER_CTL: Measure
        setRange((short) 0x00);                // +/- 2g
        setDataRate((short) 0x0a);             // 100 Hz
        refreshScalingFactor();
    }

    /**
     * Sets the measurement range in the DATA_FORMAT register.
     *
     * @param value Range value.
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
     * @param value Rate value.
     */
    public void setDataRate(final short value) {
        writeReg8((short) 0x2c, (short) (value & 0x0f));
    }

    /**
     * Updates internal scaling factor based on current resolution settings.
     */
    public void refreshScalingFactor() {
        final var format = readReg8((short) 0x31);
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
        map.put("x", x * scalingFactor * G_TO_MS2);
        map.put("y", y * scalingFactor * G_TO_MS2);
        map.put("z", z * scalingFactor * G_TO_MS2);
        return map;
    }

    /**
     * Reads the device ID from the DEVID register.
     *
     * @return The device ID (0xE5).
     */
    public short getDeviceId() {
        return readReg8((short) 0x00);
    }

    /**
     * Closes the ADXL345 device.
     * <p>
     * Puts the hardware into standby mode to save power and reduce bus noise.
     * </p>
     */
    @Override
    public void close() {
        // POWER_CTL (0x2d) bit 3 = 0 puts it in standby
        writeReg8((short) 0x2d, (short) 0x00);
        log.atDebug().log("ADXL345 hardware placed in standby mode");
    }
}
