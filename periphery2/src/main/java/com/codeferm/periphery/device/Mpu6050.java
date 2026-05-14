/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;

/**
 * MPU6050 6-axis MotionTracking device driver using FFM-based I2C communication.
 * <p>
 * This driver provides thread-safe background sensor fusion, integrating gyroscope data and applying a complementary filter (96%
 * Gyro / 4% Accel) to produce stable Euler angles.
 * </p>
 * <p>
 * <b>Implementation Details:</b>
 * <ul>
 * <li><b>Thread Safety:</b> Uses {@link AtomicReference} for lock-free snapshots and {@link ReentrantLock} for atomic hardware
 * transactions.</li>
 * <li><b>GC Efficiency:</b> Utilizes primitive arrays and {@link MpuData} records to minimize heap allocation during high-frequency
 * updates.</li>
 * <li><b>Lifecycle:</b> Implements {@link AutoCloseable} to ensure the background acquisition thread is joined and terminated.</li>
 * </ul>
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class Mpu6050 implements AutoCloseable {

    private final ReentrantLock lock = new ReentrantLock();
    private final I2cBus bus;
    private final short address;

    /**
     * Snapshot of sensor data at a specific point in time.
     */
    public record MpuData(
            double accelX, double accelY, double accelZ,
            double accelAngleX, double accelAngleY, double accelAngleZ,
            double gyroSpeedX, double gyroSpeedY, double gyroSpeedZ,
            double gyroAngleX, double gyroAngleY, double gyroAngleZ,
            double filteredX, double filteredY, double filteredZ) {

    }

    private final AtomicReference<MpuData> dataSnapshot = new AtomicReference<>(
            new MpuData(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));

    /* --- Constants & Register Map --- */
    public static final int DEFAULT_MPU6050_ADDRESS = 0x68;
    public static final double RADIAN_TO_DEGREE = 180.0 / Math.PI;

    private static final int REG_SMPRT_DIV = 0x19;
    private static final int REG_CONFIG = 0x1A;
    private static final int REG_GYRO_CONFIG = 0x1B;
    private static final int REG_ACCEL_CONFIG = 0x1C;
    private static final int REG_PWR_MGMT_1 = 0x6B;
    private static final int REG_ACCEL_XOUT_H = 0x3B;
    private static final int REG_GYRO_XOUT_H = 0x43;

    private double accelLSBSensitivity = 16384.0;
    private double gyroLSBSensitivity = 131.0;
    private long lastUpdateTime = 0;

    private Thread updatingThread;
    private volatile boolean updatingThreadStopped = true;

    /**
     * Constructs the MPU6050 driver using an existing FFM I2C bus.
     *
     * @param bus Shared FFM I2C bus.
     * @param address I2C device address (usually 0x68).
     */
    public Mpu6050(final I2cBus bus, final short address) {
        this.bus = bus;
        this.address = address;
        initializeHardware();
    }

    /**
     * Initializes the MPU6050 hardware registers to default sensitivities.
     */
    private void initializeHardware() {
        lock.lock();
        try {
            // Wake up MPU6050 (Power Management 1)
            updateRegisterValue(REG_PWR_MGMT_1, (byte) 0x00);
            updateRegisterValue(REG_SMPRT_DIV, (byte) 0x00);
            updateRegisterValue(REG_CONFIG, (byte) 0x06); // 5Hz DLPF
            updateRegisterValue(REG_GYRO_CONFIG, (byte) 0x00); // +/- 250 deg/s
            updateRegisterValue(REG_ACCEL_CONFIG, (byte) 0x00); // +/- 2g

            log.info("MPU6050 initialized at address 0x{}", Integer.toHexString(address));
        } finally {
            lock.unlock();
        }
    }

    private void updateRegisterValue(final int register, final byte value) {
        lock.lock();
        try {
            if (bus.writeReg8(address, (short) register, value) < 0) {
                throw new RuntimeException("I2C write failure at register 0x" + Integer.toHexString(register));
            }
            final var check = new byte[1];
            bus.readReg8(address, (short) register, check);
            if (check[0] != value) {
                throw new RuntimeException("Register verification failed at 0x" + Integer.toHexString(register));
            }
        } finally {
            lock.unlock();
        }
    }

    private int readWord2C(final int register) {
        final var buf = new byte[2];
        if (bus.readReg8(address, (short) register, buf) < 0) {
            throw new RuntimeException("I2C read failure at register 0x" + Integer.toHexString(register));
        }
        return (short) (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF));
    }

    private void updateValues() {
        lock.lock();
        try {
            final var now = System.currentTimeMillis();
            final var dt = Math.abs(now - lastUpdateTime) / 1000.0;
            lastUpdateTime = now;

            // Raw data acquisition
            final var ax = readWord2C(REG_ACCEL_XOUT_H) / accelLSBSensitivity;
            final var ay = readWord2C(REG_ACCEL_XOUT_H + 2) / accelLSBSensitivity;
            final var az = -readWord2C(REG_ACCEL_XOUT_H + 4) / accelLSBSensitivity;

            final var gx = readWord2C(REG_GYRO_XOUT_H) / gyroLSBSensitivity;
            final var gy = readWord2C(REG_GYRO_XOUT_H + 2) / gyroLSBSensitivity;
            final var gz = readWord2C(REG_GYRO_XOUT_H + 4) / gyroLSBSensitivity;

            final var prev = dataSnapshot.get();
            final var angAX = Math.atan2(ay, Math.sqrt(ax * ax + az * az)) * RADIAN_TO_DEGREE;
            final var angAY = Math.atan2(-ax, Math.sqrt(ay * ay + az * az)) * RADIAN_TO_DEGREE;

            // Complementary filter: 96% Gyro integration / 4% Accelerometer angle
            final var alpha = 0.96;
            final var fX = alpha * (prev.filteredX() + (gx * dt)) + (1.0 - alpha) * angAX;
            final var fY = alpha * (prev.filteredY() + (gy * dt)) + (1.0 - alpha) * angAY;

            dataSnapshot.set(new MpuData(ax, ay, az, angAX, angAY, 0,
                    gx, gy, gz,
                    prev.gyroAngleX() + (gx * dt), prev.gyroAngleY() + (gy * dt), prev.gyroAngleZ() + (gz * dt),
                    fX, fY, prev.filteredZ() + (gz * dt)));

        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves the most recent sensor fusion snapshot.
     *
     * @return Immutable record of current sensor state.
     */
    public MpuData getSnapshot() {
        return dataSnapshot.get();
    }

    /**
     * Starts the background thread for continuous sensor acquisition and fusion.
     */
    public void startUpdatingThread() {
        lock.lock();
        try {
            if (updatingThread == null || !updatingThread.isAlive()) {
                updatingThreadStopped = false;
                lastUpdateTime = System.currentTimeMillis();
                updatingThread = new Thread(() -> {
                    while (!updatingThreadStopped) {
                        try {
                            updateValues();
                            TimeUnit.MILLISECONDS.sleep(10);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (final Exception e) {
                            log.error("MPU6050 update loop error", e);
                        }
                    }
                }, "mpu6050-update-thread");
                updatingThread.setDaemon(true);
                updatingThread.start();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            updatingThreadStopped = true;
            if (updatingThread != null) {
                updatingThread.join(500);
            }
            log.info("MPU6050 driver closed.");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }
}
