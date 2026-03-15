/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.device.I2cBus;
import com.codeferm.periphery.device.Mpu6050;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Six-Axis (Gyro + Accelerometer) MEMS MotionTracking demo using FFM.
 * <p>
 * This demo showcases the use of a shared {@link I2cBus} and the thread-safe {@link Mpu6050} driver to read motion data with a
 * complementary filter.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Mpu6050Demo", mixinStandardHelpOptions = true, version = "2.0.0",
        description = "Six-Axis (Gyro + Accelerometer) MEMS MotionTracking demo using FFM")
public class Mpu6050Demo implements Callable<Integer> {

    /**
     * I2C device path.
     */
    @Option(names = {"--device"}, description = "I2C device path, ${DEFAULT-VALUE} by default.")
    private String device = "/dev/i2c-1";

    /**
     * I2C address.
     */
    @Option(names = {"--address"}, description = "I2C address (hex), ${DEFAULT-VALUE} by default.")
    private short address = Mpu6050.DEFAULT_MPU6050_ADDRESS;

    /**
     * Formats an angle value to a string.
     *
     * @param angle Angle in degrees.
     * @return Formatted string.
     */
    public static String angleToString(final double angle) {
        return "%.4f°".formatted(angle);
    }

    /**
     * Formats an acceleration value to a string.
     *
     * @param accel Acceleration in g.
     * @return Formatted string.
     */
    public static String accelToString(final double accel) {
        return "%.6fg".formatted(accel);
    }

    /**
     * Formats an angular speed value to a string.
     *
     * @param speed Speed in deg/s.
     * @return Formatted string.
     */
    public static String angularSpeedToString(final double speed) {
        return "%.4f°/s".formatted(speed);
    }

    /**
     * Combines X, Y, and Z string representations for logging.
     *
     * @param x X value string.
     * @param y Y value string.
     * @param z Z value string.
     * @return Combined tab-separated string.
     */
    public static String xyzValuesToString(final String x, final String y, final String z) {
        return "x: %s\ty: %s\tz: %s".formatted(x, y, z);
    }

    /**
     * Execution logic for the demo.
     *
     * @return Exit code.
     * @throws InterruptedException If the sleep is interrupted.
     */
    @Override
    public Integer call() throws InterruptedException {
        var exitCode = 0;
        log.info("Starting MPU6050 demo on {} at 0x{}", device, Integer.toHexString(address));

        // Use I2cBus with a 16-byte buffer (sufficient for MPU6050 bursts)
        try (final var bus = new I2cBus(device, 16); final var mpu = new Mpu6050(bus, address)) {

            log.info("Calibrating sensors (keep device still)...");
            // Optional: You could add a mpu.calibrate() method here if desired

            mpu.startUpdatingThread();
            log.info("Reading sensor data for 30 seconds...");

            for (int i = 0; i < 10; i++) {
                // Capture a consistent snapshot of the fusion results
                final var data = mpu.getSnapshot();

                log.info("--- Sensor Snapshot {} ---", i + 1);

                log.info("Accelerometer Angles: {}", xyzValuesToString(
                        angleToString(data.accelAngleX()),
                        angleToString(data.accelAngleY()),
                        angleToString(data.accelAngleZ())));

                log.info("Raw Accelerations:    {}", xyzValuesToString(
                        accelToString(data.accelX()),
                        accelToString(data.accelY()),
                        accelToString(data.accelZ())));

                log.info("Gyroscope Angles:     {}", xyzValuesToString(
                        angleToString(data.gyroAngleX()),
                        angleToString(data.gyroAngleY()),
                        angleToString(data.gyroAngleZ())));

                log.info("Angular Speeds:       {}", xyzValuesToString(
                        angularSpeedToString(data.gyroSpeedX()),
                        angularSpeedToString(data.gyroSpeedY()),
                        angularSpeedToString(data.gyroSpeedZ())));

                log.info("Filtered Angles:      {}", xyzValuesToString(
                        angleToString(data.filteredX()),
                        angleToString(data.filteredY()),
                        angleToString(data.filteredZ())));

                TimeUnit.SECONDS.sleep(3);
            }

        } catch (Exception e) {
            log.error("Fatal error during demo: {}", e.getMessage());
            exitCode = 1;
        }

        log.info("Demo complete.");
        return exitCode;
    }

    /**
     * Main entry point for the MPU6050 FFM Demo.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        final var cmd = new CommandLine(new Mpu6050Demo());
        // Support hex decoding for address
        cmd.registerConverter(Short.class, Short::decode);
        cmd.registerConverter(short.class, Short::decode);
        System.exit(cmd.execute(args));
    }
}
