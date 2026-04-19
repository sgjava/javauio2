/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * DHT11 Temperature and Humidity sensor device using the Linux Industrial I/O (IIO) kernel driver.
 * <p>
 * This class provides a thread-safe, robust interface to DHT11 sensors. By offloading protocol timing to the kernel, it avoids the
 * pitfalls of user-space bit-banging and scheduler jitter.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class Dht11 {

    /**
     * Reentrant lock to ensure atomic reads of sensor data.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Path to the temperature input file.
     */
    private final Path tempPath;

    /**
     * Path to the humidity input file.
     */
    private final Path humPath;

    /**
     * Most recently read temperature in Celsius (Internal base unit).
     */
    private double temperatureC;

    /**
     * Most recently read relative humidity in percent.
     */
    @Getter
    private double humidity;

    /**
     * Constructs the DHT11 device interface.
     *
     * @param iioDevice The IIO device name (e.g., "iio:device0").
     */
    public Dht11(final String iioDevice) {
        this.tempPath = Path.of("/sys/bus/iio/devices", iioDevice, "in_temp_input");
        this.humPath = Path.of("/sys/bus/iio/devices", iioDevice, "in_humidityrelative_input");
        log.debug("DHT11 IIO driver initialized: {}", iioDevice);
    }

    /**
     * Returns the temperature in Fahrenheit (Default).
     *
     * @return Temperature in °F.
     */
    public double getTemperature() {
        return (temperatureC * 1.8) + 32.0;
    }

    /**
     * Returns the temperature in Celsius.
     *
     * @return Temperature in °C.
     */
    public double getTemperatureC() {
        return temperatureC;
    }

    /**
     * Reads the current temperature and humidity from the kernel driver.
     *
     * @return True if read was successful, false otherwise.
     */
    public boolean read() {
        lock.lock();
        try {
            // Kernel IIO values are in milli-units (e.g., 25800 = 25.8 C)
            final var rawTemp = Files.readString(tempPath).trim();
            final var rawHum = Files.readString(humPath).trim();

            this.temperatureC = Integer.parseInt(rawTemp) / 1000.0;
            this.humidity = Integer.parseInt(rawHum) / 1000.0;

            return true;
        } catch (final IOException | NumberFormatException e) {
            log.error("Failed to read DHT11 IIO files: {}", e.getMessage());
            return false;
        } finally {
            lock.unlock();
        }
    }
}
