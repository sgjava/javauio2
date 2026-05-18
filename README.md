```text
# Java UIO 2 (FFM)

![Title](images/title.png)

[![JDK 25 LTS](https://img.shields.io/badge/JDK-25_LTS-orange.svg)](https://openjdk.java.net/projects/jdk/25/)
[![Ubuntu 24.04](https://img.shields.io/badge/Ubuntu-24.04_Noble-blue.svg)](https://ubuntu.com/blog/whats-new-in-security-for-ubuntu-24-04-lts)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Arch: ARM64](https://img.shields.io/badge/Arch-ARM64%20(v8)-green.svg)](https://github.com/sgjava/javauio2)
[![Arch: ARM32](https://img.shields.io/badge/Arch-ARM32%20(v7)-yellow.svg)](https://github.com/sgjava/javauio2)
[![Arch: X86_64](https://img.shields.io/badge/Arch-X86__64-blue.svg)](https://github.com/sgjava/javauio2)
[![API: FFM/Panama](https://img.shields.io/badge/API-FFM%2FPanama-red.svg)](https://openjdk.org/projects/panama/)
[![Interface: Linux CDEV](https://img.shields.io/badge/Interface-Linux%20CDEV-lightgrey.svg)](https://git.kernel.org/pub/scm/libs/libgpiod/libgpiod.git)

**Java UIO 2** is the next-generation evolution of the Java UIO project, rebuilt from the ground up to leverage the **Foreign Function & Memory API (FFM)**. By moving beyond traditional JNI, Java UIO 2 achieves unprecedented performance and hardware-level accuracy for Linux Userspace IO. Engineered for **JDK 25**, it provides a cutting-edge cross-platform solution for modern embedded systems.

## 🚀 The New Standard
This project adheres to rigorous development standards to ensure production-grade reliability and performance:
* **Modern Java:** Full utilization of modern Java and the latest FFM features. Every device driver and application structure strictly uses `var`, `final`, and explicit Project Panama patterns.
* **Zero Allocation:** Frame-based operations (like SSD1331 rendering, animations, and video playbacks) use pre-allocated buffers and window slicing to eliminate runtime heap allocation and GC pressure during high-speed native I/O.
* **Deterministic Lifecycle Synchronization (Ctrl+C Shielding):** Incorporates explicit, application-level thread signal interception and sequential containment hooks. This ensures hot rendering execution loops drop completely out of native FFM space and yield unmanaged memory control *before* parent context hardware teardown logic unmaps file descriptors or zeros driver context references, entirely averting `SIGSEGV` address faults on exit for all native devices and u8g2 instances.
* **Clean Architecture:** **No native loaders in device classes.** Native library loading constraints are restricted solely to application execution contexts (i.e., demos), preserving hardware drivers as lean, pure mapping wrappers.
* **Complete Documentation:** 100% rigorous Javadoc coverage across all public variables, layouts, and API methods.

---

## 🏗️ Architectural Contrast: Why Java UIO 2?

| Feature | Pi4J (v4.0+) | diozero (v1.4+) | **Java UIO 2** |
| :--- | :--- | :--- | :--- |
| **Model** | **Provider-Centric.** Relies on a plugin architecture to map hardware. | **Device-Centric.** Uses a "Factory" abstraction to wrap pins in objects. | **Kernel-Direct.** Treats the Linux Kernel ABI as the only provider. |
| **FFM Integration** | **Plugin Level.** FFM is an optional provider module. | **JNI Core.** Primarily utilizes JNI/JNA for native access. | **Native FFM.** Built specifically for Project Panama as the core engine. |
| **Board Support** | Board-specific definitions/configs often required. | Broad, but requires factory logic for each SoC. | **Universal.** If it runs a standard Linux kernel, it works instantly. |
| **Graphics** | Community-ported Java drivers. | High-level/basic shape support. | **Deep u8g2 Binding.** Full C-performance for expanded native displays. |
| **Portability** | Heavyweight (Core + Provider + Config). | Lightweight core, but SoC-specific factories. | **Ultra-Lightweight.** Zero-dependency bridge to Linux interfaces. |

### 1. Universal Kernel-Standardized I/O
Most libraries require a "Provider" or specific plugin configuration for every new single-board computer iteration. **Java UIO 2** bypasses this middleman layer completely. By targeting the **Standard Linux Kernel ABI** (Character Devices via `PERIPHERY_GPIO_CDEV_SUPPORT` and standard UIO), any hardware target platform running a modern Linux kernel is supported immediately out-of-the-box. The Linux Kernel is the only hardware provider required.

### 2. Deep Graphics with u8g2 & Native Device Stacks
While traditional libraries stop at "Blinky" abstractions, Java UIO 2 delivers high-performance graphic stack options. By natively binding the industry-standard **u8g2** library via Project Panama and implementing zero-copy unmanaged memory bridges for color OLED drivers like the **SSD1331**, developers can drive complex custom canvas buffers, 3D wireframe animations, and full-motion raw video streams at maximum hardware bus limits.

### 3. Hardware-Accurate Native Sizing
A major structural failure point in traditional libraries is their brittle reliance on manual JNI header mapping. Java UIO 2 incorporates an explicit **Native Sizer** step directly into the build pipeline (utilizing native environments or QEMU cross-emulation). This guarantees that generated FFM `MemoryLayout` offsets are bit-perfect for the targeted CPU architecture (ARM64, ARM32, or x86_64), fully preventing alignment traps and native memory crashes.

---

## 🛠️ Performance Benchmark (Pine64 ARM64)
In raw performance testing on the Pine A64 (Cortex-A53), the Panama FFM implementation demonstrated a massive leap over established JNI methods:

| Operation | HawtJNI (Legacy JNI) | **Java UIO 2 (FFM)** | **Improvement** |
| :--- | :--- | :--- | :--- |
| **GPIO Writes** | ~292k ops/sec | **~561k ops/sec** | **+91.5%** |
| **GPIO Reads** | ~400k ops/sec | **~582k ops/sec** | **+45.5%** |

*Note: Benchmarks were executed under single-core isolated constraints. With Project Panama FFM, the Java-to-Native bridge is no longer the computing bottleneck; execution performance boundaries are now bound exclusively by individual Linux Kernel ioctl and bus transaction latencies.*

---

## 🌍 Architecture Support Matrix (JDK 25)

| Architecture | JNI (Java UIO) | **FFM (Java UIO 2)** |
| :--- | :---: | :---: |
| **ARM32 (v7)** | ✅ Supported | ⏳ Pending Linker |
| **ARM64 (v8)** | ✅ Supported | ✅ **Recommended** |
| **X86_64** | ✅ Supported | ✅ **Recommended** |

---

## 📦 Project Depth
This repository contains more than just a library; it includes exhaustive, real-world application structures and newly added device driver profiles designed to demonstrate high-stress hardware control scenarios:
* **Video Playback:** Native-heap pre-cached raw video stream playback displaying complex vector updates with zero-allocation looping mechanisms.
* **3D Wireframe Graphics:** Real-time 3D perspective projection cube rendering featuring automated clip boundaries, dynamic vector scaling, and high-frequency coordinate manipulation.
* **Atari Centipede Game Engine:** Fast-paced game mechanics demonstrating zero-allocation entity tracking and clean user input integration over native display buses.
* **Native Drivers & Protocols:** Comprehensive direct implementations handling both hardware-driven peripheral layers (where operations like SPI chip select are delegated cleanly to the kernel subsystem) and software bit-banged fallback architectures (`I2CHW`, `I2CSW`, `SPIHW`, `SPISW`).
* **Robust Hardware Catalog:** Out-of-the-box native driver implementations spanning an array of diverse hardware components, now including the newly expanded portfolio of display controllers (such as **SSD1331**, **SSD1306**, and monochrome variants), protocol-aware context engines, and robust cross-bus device abstractions.

### Download and Build
```bash
# Download project
sudo apt install git
cd ~/
git clone --depth 1 [https://github.com/sgjava/javauio2.git](https://github.com/sgjava/javauio2.git)

# Setup and Install
cd ~/javauio2/scripts
./install-java.sh
./setup-permissions.sh # ARM only
sudo reboot

# Build with Maven
cd ~/javauio2
mvn clean install # X86_64 default
# Use -P arm64 (aarch64) or -P arm32 (armhf) for target platform profiles
