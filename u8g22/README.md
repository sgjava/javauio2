![Title](images/title.png)

U8g2 2 for Java is a high-performance FFM wrapper based on [U8g2](https://github.com/olikraus/u8g2): the definitive library for monochrome displays.

## Architecture: Why Java UIO 2 U8g2 Doesn't Require AWT

Some popular Java libraries use a wrapper around java.awt.Graphics2D. This forces the JVM to load the entire AWT/headless subsystem just to draw a single pixel. JavaUIO U8g2 bypasses this entirely because:

* Native Rendering Engine: All drawing logic (lines, circles, boxes, and fonts) is handled by the native C library (U8g2). When you call u8g2.drawStr(), you are passing coordinates and strings directly to optimized C code that manipulates a memory buffer.
* Direct Bit Mapping: U8g2 is designed for monochrome displays. It draws directly into a 1-bit-per-pixel buffer. Java’s BufferedImage typically operates in 32-bit (ARGB) or 16-bit color, requiring a "downsampling" step (conversion) that is computationally expensive on SBCs like the Raspberry Pi.
* No Headless Requirement: On many Linux distributions, running AWT code requires installing libawt or openjdk-X-jre-headless. JavaUIO U8g2 only requires the tiny native shared library (.so) inside the jar.
* If you want to use AWT I have you covered. Look at [BufImage](https://github.com/sgjava/javauio/blob/main/demo/src/main/java/com/codeferm/u8g2/demo/BufImage.java) demo.

## Key Features

* **Massive Hardware Support:** Access to **~200 monochrome display controllers** and over **700 high-quality fonts** out of the box.
* **Authentic API:** The Java code strictly follows the C API. If you have used U8g2 in C, C++, or NodeMcu (Lua), the methods will be immediately familiar. No "heavy" Java abstractions—just the raw power of U8g2.
* **Dynamic Configuration:** The [Display](https://github.com/sgjava/javauio/blob/main/u8g2/src/main/java/com/codeferm/u8g2/Display.java) class allows for runtime setup and font selection. Build applications that don't need to know the display or font type at compile time.
* **Desktop Simulation:** Works with **SDL 2**, allowing you to develop and debug your UI on your desktop without needing a physical display or a logic analyzer.
* **Performance & Reliability:** This includes my PRs for the [arm-linux](https://github.com/olikraus/u8g2/tree/master/sys/arm-linux) port, making it thread-safe and multi-display capable. It also features optimized I2C and SPI software drivers for embedded Linux.

Do it all yourself U8g2 style or look at the [Base](https://github.com/sgjava/javauio2/blob/main/demo/src/main/java/com/codeferm/u8g2/demo/Base.java) class in Demo module.

```
showText(u8g2, "Welcome to Java 25 FFM!"); 
```

<img src="images/u8g2.jpg" width="300"/>


Check out all the [demos](https://github.com/sgjava/javauio/tree/main/demo/src/main/java/com/codeferm/u8g2/demo).
You will find quite a sophisticated selection.

SSD1306 sendBuffer performance based on JDK 25 and Pine A64.

|Setup                       |Type |Bus KHz | FPS  |
| -------------------------- | --- | ------ | ---- |
|SSD1306_I2C_128X64_NONAME   |HW   |     100|  8.85|
|SSD1306_I2C_128X64_NONAME   |HW   |     400| 30.30|
|SSD1306_I2C_128X64_NONAME   |SW   |     189| 14.49|
|SSD1306_I2C_128X32_UNIVISION|SW   |     189| 28.57|
|SSD1306_128X64_NONAME       |HW   |     500| 50.00|
|SSD1306_128X64_NONAME       |HW   |    1000|100.00|
|SSD1306_128X64_NONAME       |HW   |    2000|200.00|
|SSD1306_128X64_NONAME       |SW   |     189| 19.61|

## Use Java u8g2 in your own Maven projects
After bulding Java u8g2 simpily add the following artifact:
```
<groupId>com.codeferm</groupId>
<artifactId>u8g22</artifactId>
<version>1.0.0-SNAPSHOT</version>
```
