![Title](images/title.png)

Periphery is a high performance library for GPIO, LED, PWM, SPI, I2C, MMIO
and Serial peripheral I/O interface access in userspace Linux.
* Cross platform MMIO GPIO that doesn't require one off code for each board. Only
a simple property file is required to map registers.

<img src="images/periphery.png" width="300"/>

NanoPi Duo rigged up to test Periphery including serial and SPI loopbacks, MPU6050 to test I2C and Led.
Built in button and system Led can also be tested all without mocks.

Periphery will be targeting Armbian, but the code should work with most
Linux distributions. Demo apps are included that illustrate how to leverage the
bindings. The idea is to have consistent APIs across
[C](https://github.com/vsergeev/c-periphery),
[Python](https://github.com/vsergeev/python-periphery),
[Lua](https://github.com/vsergeev/lua-periphery),
[Dart](https://github.com/pezi/dart_periphery) and JVM languages without having
to use one off board specific drivers, 
[deprecated wiringPi](http://wiringpi.com/wiringpi-deprecated) or the
[deprecated sysfs](https://www.kernel.org/doc/html/latest/admin-guide/gpio/sysfs.html)
interface.

# Raspberry Pi configuration under Armbian
Use `/boot/firmware/config.txt` to configure Pi instead of `/boot/armbianEnv.txt`.

## Armbian and built in buttons
On the NanoPi Duo the built in button causes it to shutdown by default. You can
remove the r_gpio_keys section in the DTB as follows (this may work on other SBCs,
but you'll need to know the correct dtb file and section to remove) :
* `cd /boot/dtb`
* `sudo cp sun8i-h2-plus-nanopi-duo.dtb sun8i-h2-plus-nanopi-duo.dtb.old`
* `sudo dtc -@ -I dtb -O dts -o sun8i-h2-plus-nanopi-duo.dts sun8i-h2-plus-nanopi-duo.dtb`
* `sudo nano sun8i-h2-plus-nanopi-duo.dts`
    * Remove `gpio-keys` section
* `sudo dtc -@ -I dts -O dtb -o sun8i-h2-plus-nanopi-duo.dtb sun8i-h2-plus-nanopi-duo.dts`
* `reboot`

## Armbian I2C frequency
On Armbian you can change I2c frequency. Seems only 100 KHz and 400 KHz
is supported by kernel. Here we do it on the NanoPi Duo.
* Use same steps as above to copy dtb and edit it.
* `sudo nano sun8i-h2-plus-nanopi-duo.dts`
    * Search for `i2c@` sections and add 400 KHz clock frequency.
    * `clock-frequency = <400000>;`
* `sudo dtc -@ -I dts -O dtb -o sun8i-h2-plus-nanopi-duo.dtb sun8i-h2-plus-nanopi-duo.dts`
* `reboot`

## Armbian SPI
Make the following changes to /boot/armbianEnv.txt as needed.
* `param_spidev_spi_bus=1` to change to /dev/spidev1.0 for Duo
* `extraargs=spidev.bufsiz=65536` increase buffer size from 4K. Verify with `cat /sys/module/spidev/parameters/bufsiz`

## SSD1331 OLED support
SSD1331 driver provides fast Java 2D buffered updates. Easily do over 120 FPS on older/slower
SBC like NanoPi Duo with very little CPU usage. Look at the demo project for examples.

## High performance GPIO using MMIO
I have created a generic way to achieve fast GPIO for times when performance (bit
banging, software based PWM, low CPU latency, etc) is required. I have written a
mapper, so you can extract the  data register masks without having to do it by
hand from the datasheet. Doing this totally by hand is tedious and error prone.
The method I use is using a well know interface (GPIO device) to make changes
and detecting register deltas. You still need to create a [input file](https://github.com/sgjava/javauio2/blob/main/periphery/src/main/resources/duo.properties)
with various board specific parameters. Make sure you disable all hardware in
armbian-config System, Hardware and remove console=serial from
/boot/armbianEnv.txt. You want multi-function pins to act as GPIO pins.

Check out [Tools](https://github.com/sgjava/javauio/tree/main/tools) module for examples of running the MMIO GPIO tools.

As you can see above the same performance test code works on a 32 bit H2+ and a
64 bit H5 CPU. This means almost all boards can be easily supported with
the right input file. This is probably the only high performance GPIO code that
is truly cross platform. No custom adapters or other one off code is required
currently. Also, I use the same pin numbers as the GPIO device, so no goofy
wiringPi or BCM pin numbering. Keep in mind that only one core is used, so the 
CPU will never exceed 25% on a quad core system.

If you want to map your own board you start by getting the data sheet and
finding the data registers. I've written a little memory tool
[MemScan](https://github.com/sgjava/javauio/blob/main/tools/src/main/java/com/codeferm/periphery/mmio/MemScan.java)
that will allow you to see what bits change for a range of registers using mode,
data and pull operations.

## GPIO Performance using Perf
Note that most performance tests focus on writes and not CPU overhead, so it's
hard to compare. Technically you will actually be doing something like bit
banging to simulate a protocol, so you need extra CPU bandwidth to do that.
Please note write frequency is based on square wave (rapid on/off). You can
increase clock speed to improve performance on some boards. I used the OS
defaults. Speed was validated on an oscilloscope, so Perf test may show better
performance. Buffer write is true bits written, not toggled. 

|SBC               |OS              |CPU Freq|GPIOD Write KHz|Buffer Write KHz|Average CPU|
| ---------------- | -------------- | ------ | ------------- | -------------- | --------- |
|Nano Pi Duo v1.0  |Armbian Resolute|1.0 GHz |37             |616             |25%        |


## GPIO Performance & Architecture Notes

When performing high-frequency GPIO operations on **ARM32** architectures, you may notice that individual per-operation calls (such as calling `gpio_write()` or `gpio_read()` in a tight loop via Foreign Function & Memory (FFM) or JNI) experience throughput limitations. 

### Why ARM32 GPIO Iteration is Slower
1. **ABI Transition Overhead & Register Pressure:** ARM32 is a 32-bit architecture with a severely constrained pool of general-purpose registers compared to 64-bit systems. FFM/Panama dynamic downcall adapters must constantly spill and reload registers to conform to complex `arm-linux-gnueabihf` calling conventions.
2. **The Per-Call Boundary Tax:** Executing iterative hardware operations line-by-line forces a continuous stream of cross-boundary transitions and kernel `ioctl()` system calls (`/dev/gpiochipN` character device context switches). On resource-constrained 32-bit processors, this cumulative latency heavily throttles throughput.

### The Solution: Bulk Native Helpers
To achieve maximum throughput (scaling into the megahertz range), this project utilizes custom C helper functions (located in `src/main/native/helper.c`). 

Instead of hammering the FFM boundary or the kernel driver per-bit/per-sample from Java, the entire data buffer or batch sequence is passed down in a **single native downcall**. The underlying C code processes the tight loop natively in optimized machine code, completely bypassing cross-boundary overhead and maximizing hardware toggle speed.

## How GPIO pins are mapped
This is based on testing on a NanoPi Duo. gpiochip0 starts at 0 and gpiochip1
starts at 352. Consider the following table:

|Name                           |Chip Name |dev |sysfs|
| ----------------------------- | -------- | -- | --- |
|DEBUG_TX(UART_TXD0)/GPIOA4     |gpiochip0 | 004|  004|
|DEBUG_RX(UART_RXD0)/GPIOA5/PWM0|gpiochip0 | 005|  005|
|I2C0_SCL/GPIOA11               |gpiochip0 | 011|  011|
|I2C0_SDA/GPIOA12               |gpiochip0 | 012|  012|
|UART3_TX/SPI1_CS/GPIOA13       |gpiochip0 | 013|  013|
|UART3_RX/SPI1_CLK/GPIOA14      |gpiochip0 | 014|  014|
|UART3_RTS/SPI1_MOSI/GPIOA15    |gpiochip0 | 015|  015|
|UART3_CTS/SPI1_MISO/GPIOA16    |gpiochip0 | 016|  016|
|UART1_TX/GPIOG6                |gpiochip0 | 198|  198|
|UART1_RX/GPIOG7                |gpiochip0 | 199|  199|
|GPIOG11                        |gpiochip0 | 203|  203|
|ON BOARD BUTTON                |gpiochip1 | 003|  355|
|GPIOL11/IR-RX                  |gpiochip1 | 011|  363|

So basically you just need to know the starting number for each chip and realize
GPIO character devices always starts at 0 and calculate the offset. Thus gpiochip1
starts at 352 and the on board button is at 355, so 355 - 352 = 3 for GPIO
character device.

## Use Periphery in your own Maven projects
After bulding Periphery simpily add the following artifact:
```
<groupId>com.codeferm</groupId>
<artifactId>periphery2</artifactId>
<version>1.0.0-SNAPSHOT</version>
```
