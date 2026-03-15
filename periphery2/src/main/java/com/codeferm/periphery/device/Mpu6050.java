/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;

/**
 * MPU6050 6-axis MotionTracking device driver.
 * <p>
 * This implementation utilizes the {@link I2cBus} FFM-based wrapper for hardware communication. It provides a thread-safe
 * background update mechanism to integrate gyroscope data and apply a complementary filter to accelerometer angles.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class Mpu6050 implements AutoCloseable {

    /**
     * Reentrant lock for thread-safe access to internal state during updates.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Shared I2C bus wrapper.
     */
    private final I2cBus bus;

    /**
     * I2C address of the MPU6050.
     */
    private final short address;

    /**
     * Default I2C address for MPU6050 (AD0 low).
     */
    public static final int DEFAULT_MPU6050_ADDRESS = 0x68;

    /**
     * Default Digital Low Pass Filter configuration (5Hz bandwidth).
     */
    public static final int DEFAULT_DLPF_CFG = 0x06;

    /**
     * Default Sample Rate Divider.
     */
    public static final int DEFAULT_SMPLRT_DIV = 0x00;

    /**
     * Multiplier to convert radians to degrees.
     */
    public static final double RADIAN_TO_DEGREE = 180. / Math.PI;

    /**
     * Constant Z angle for accelerometer calculations.
     */
    private static final double ACCEL_Z_ANGLE = 0;

    /* --- Register Map --- */
    /**
     * Sample Rate Divider register.
     */
    public static final int MPU6050_REG_ADDR_SMPRT_DIV = 0x19;
    /**
     * Configuration register (DLPF).
     */
    public static final int MPU6050_REG_ADDR_CONFIG = 0x1A;
    /**
     * Gyroscope configuration register.
     */
    public static final int MPU6050_REG_ADDR_GYRO_CONFIG = 0x1B;
    /**
     * Accelerometer configuration register.
     */
    public static final int MPU6050_REG_ADDR_ACCEL_CONFIG = 0x1C;
    /**
     * Interrupt enable register.
     */
    public static final int MPU6050_REG_ADDR_INT_ENABLE = 0x38;
    /**
     * Power management 1 register.
     */
    public static final int MPU6050_REG_ADDR_PWR_MGMT_1 = 0x6B;
    /**
     * Power management 2 register.
     */
    public static final int MPU6050_REG_ADDR_PWR_MGMT_2 = 0x6C;
    /**
     * Accelerometer X-axis high byte register.
     */
    public static final int MPU6050_REG_ADDR_ACCEL_XOUT_H = 0x3B;
    /**
     * Gyroscope X-axis high byte register.
     */
    public static final int MPU6050_REG_ADDR_GYRO_XOUT_H = 0x43;

    /**
     * Current DLPF configuration.
     */
    private int dlpfCfg;
    /**
     * Current Sample Rate Divider.
     */
    private int smplrtDiv;
    /**
     * Accelerometer LSB sensitivity based on full scale range.
     */
    private double accelLSBSensitivity;
    /**
     * Gyroscope LSB sensitivity based on full scale range.
     */
    private double gyroLSBSensitivity;

    /**
     * Calibrated Gyroscope X offset.
     */
    private double gyroAngularSpeedOffsetX;
    /**
     * Calibrated Gyroscope Y offset.
     */
    private double gyroAngularSpeedOffsetY;
    /**
     * Calibrated Gyroscope Z offset.
     */
    private double gyroAngularSpeedOffsetZ;

    /**
     * Background thread for sensor fusion.
     */
    private Thread updatingThread = null;
    /**
     * Flag to signal the background thread to stop.
     */
    private volatile boolean updatingThreadStopped = true;
    /**
     * System timestamp of the last data acquisition.
     */
    private long lastUpdateTime = 0;

    /**
     * Record containing a complete snapshot of MPU sensor data.
     */
    public record MpuData(
            double accelX, double accelY, double accelZ,
            double accelAngleX, double accelAngleY, double accelAngleZ,
            double gyroSpeedX, double gyroSpeedY, double gyroSpeedZ,
            double gyroAngleX, double gyroAngleY, double gyroAngleZ,
            double filteredX, double filteredY, double filteredZ) {

    }

    /**
     * Thread-safe reference to the most recent sensor data.
     */
    private final AtomicReference<MpuData> dataSnapshot = new AtomicReference<>(
            new MpuData(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));

    /**
     * Constructs MPU6050 driver.
     *
     * @param bus Shared I2C bus.
     * @param address Device address.
     */
    public Mpu6050(final I2cBus bus, final short address) {
        this.bus = bus;
        this.address = address;
        initializeHardware();
    }

    /**
     * Sets up the device power state and sensor sensitivities.
     */
    private void initializeHardware() {
        lock.lock();
        try {
            dlpfCfg = DEFAULT_DLPF_CFG;
            smplrtDiv = DEFAULT_SMPLRT_DIV;
            // Wake up MPU6050
            updateRegisterValue(MPU6050_REG_ADDR_PWR_MGMT_1, (byte) 0x00);
            updateRegisterValue(MPU6050_REG_ADDR_SMPRT_DIV, (byte) smplrtDiv);
            setDLPFConfig(dlpfCfg);
            // Default gyro sensitivity: 131 LSB / (deg/s)
            gyroLSBSensitivity = 131.;
            updateRegisterValue(MPU6050_REG_ADDR_GYRO_CONFIG, (byte) 0x00);
            // Default accel sensitivity: 16384 LSB / g
            accelLSBSensitivity = 16384.;
            updateRegisterValue(MPU6050_REG_ADDR_ACCEL_CONFIG, (byte) 0x00);
            updateRegisterValue(MPU6050_REG_ADDR_INT_ENABLE, (byte) 0x00);
            updateRegisterValue(MPU6050_REG_ADDR_PWR_MGMT_2, (byte) 0x00);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Updates a register value and verifies the write operation.
     *
     * @param register Register address.
     * @param value Value to write.
     */
    public void updateRegisterValue(final int register, final byte value) {
        lock.lock();
        try {
            if (bus.writeReg8(address, (short) register, value) < 0) {
                throw new RuntimeException("I2C write failure at 0x%s".formatted(Integer.toHexString(register)));
            }
            final var check = new byte[1];
            bus.readReg8(address, (short) register, check);
            if (check[0] != value) {
                throw new RuntimeException("Register verification failed at 0x%s".formatted(Integer.toHexString(register)));
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads 16-bit signed value from two consecutive 8-bit registers.
     *
     * @param register Starting register address.
     * @return 16-bit signed integer.
     */
    private int readWord2C(final int register) {
        final var buf = new byte[2];
        if (bus.readReg8(address, (short) register, buf) < 0) {
            throw new RuntimeException("I2C read failure at 0x%s".formatted(Integer.toHexString(register)));
        }
        // Big-endian assembly (High byte first)
        return (short) (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF));
    }

    /**
     * Returns the latest scaled accelerometer readings in g.
     *
     * @return Array of [x, y, z] values.
     */
    public double[] readScaledAccelerometerValues() {
        lock.lock();
        try {
            return new double[]{
                readWord2C(MPU6050_REG_ADDR_ACCEL_XOUT_H) / accelLSBSensitivity,
                readWord2C(MPU6050_REG_ADDR_ACCEL_XOUT_H + 2) / accelLSBSensitivity,
                -readWord2C(MPU6050_REG_ADDR_ACCEL_XOUT_H + 4) / accelLSBSensitivity
            };
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the latest scaled gyroscope readings in degrees per second.
     *
     * @return Array of [x, y, z] values.
     */
    public double[] readScaledGyroscopeValues() {
        lock.lock();
        try {
            return new double[]{
                readWord2C(MPU6050_REG_ADDR_GYRO_XOUT_H) / gyroLSBSensitivity,
                readWord2C(MPU6050_REG_ADDR_GYRO_XOUT_H + 2) / gyroLSBSensitivity,
                readWord2C(MPU6050_REG_ADDR_GYRO_XOUT_H + 4) / gyroLSBSensitivity
            };
        } finally {
            lock.unlock();
        }
    }

    /**
     * Updates the Digital Low Pass Filter configuration.
     *
     * @param dlpfConfig Value between 0 and 7.
     */
    public final void setDLPFConfig(final int dlpfConfig) {
        if (dlpfConfig > 7 || dlpfConfig < 0) {
            throw new IllegalArgumentException("DLPF config must be in range 0..7");
        }
        lock.lock();
        try {
            this.dlpfCfg = dlpfConfig;
            updateRegisterValue(MPU6050_REG_ADDR_CONFIG, (byte) dlpfCfg);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Core update logic. Performs sensor fusion and updates the data snapshot.
     */
    private void updateValues() {
        lock.lock();
        try {
            final var acc = readScaledAccelerometerValues();
            final var gyro = readScaledGyroscopeValues();
            final var now = System.currentTimeMillis();
            final var dt = Math.abs(now - lastUpdateTime) / 1000.;
            lastUpdateTime = now;

            final var gSX = gyro[0] - gyroAngularSpeedOffsetX;
            final var gSY = gyro[1] - gyroAngularSpeedOffsetY;
            final var gSZ = gyro[2] - gyroAngularSpeedOffsetZ;

            final var prev = dataSnapshot.get();
            final var angAX = getAccelXAngle(acc[0], acc[1], acc[2]);
            final var angAY = getAccelYAngle(acc[0], acc[1], acc[2]);

            // Complementary filter: 96% gyro, 4% accelerometer
            final var alpha = 0.96;
            final var fX = alpha * (prev.filteredX() + (gSX * dt)) + (1. - alpha) * angAX;
            final var fY = alpha * (prev.filteredY() + (gSY * dt)) + (1. - alpha) * angAY;

            dataSnapshot.set(new MpuData(acc[0], acc[1], acc[2], angAX, angAY, ACCEL_Z_ANGLE,
                    gSX, gSY, gSZ,
                    prev.gyroAngleX() + (gSX * dt), prev.gyroAngleY() + (gSY * dt), prev.gyroAngleZ() + (gSZ * dt),
                    fX, fY, prev.filteredZ() + (gSZ * dt)));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Calculates magnitude of two components.
     *
     * @param a Component A.
     * @param b Component B.
     * @return Magnitude.
     */
    private double distance(final double a, final double b) {
        return Math.sqrt(a * a + b * b);
    }

    /**
     * Calculates X angle from accelerometer components.
     */
    private double getAccelXAngle(final double x, final double y, final double z) {
        var radians = Math.atan2(y, distance(x, z));
        final var delta = (y >= 0) ? (z >= 0 ? 0 : 180) : (z <= 0 ? 180 : 360);
        if ((y < 0 && z <= 0) || (y >= 0 && z < 0)) {
            radians *= -1;
        }
        return radians * RADIAN_TO_DEGREE + delta;
    }

    /**
     * Calculates Y angle from accelerometer components.
     */
    private double getAccelYAngle(final double x, final double y, final double z) {
        var tan = -1 * x / distance(y, z);
        final var delta = (x <= 0) ? (z >= 0 ? 0 : 180) : (z <= 0 ? 180 : 360);
        if ((x > 0 && z <= 0) || (x <= 0 && z < 0)) {
            tan *= -1;
        }
        return Math.atan(tan) * RADIAN_TO_DEGREE + delta;
    }

    /**
     * Returns the latest sensor snapshot.
     *
     * @return MpuData snapshot.
     */
    public MpuData getSnapshot() {
        return dataSnapshot.get();
    }

    /**
     * Starts the background acquisition thread.
     */
    public void startUpdatingThread() {
        lock.lock();
        try {
            if (updatingThread == null || !updatingThread.isAlive()) {
                updatingThreadStopped = false;
                lastUpdateTime = System.currentTimeMillis();
                updatingThread = new Thread(() -> {
                    while (!updatingThreadStopped) {
                        updateValues();
                        try {
                            TimeUnit.MILLISECONDS.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
                updatingThread.setName("mpu6050-update-thread");
                updatingThread.start();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Signals the background thread to stop and waits for termination.
     */
    @Override
    public void close() {
        lock.lock();
        try {
            updatingThreadStopped = true;
            if (updatingThread != null) {
                try {
                    updatingThread.join(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
