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

## 🚀 The FFM Evolution
While the original Java UIO set the standard for JNI-based IO, Java UIO 2 moves into the future with **Project Panama**. By utilizing FFM (refined in JDK 25), we eliminate the JNI "tax," allowing the JVM to optimize native calls with the same efficiency as standard Java code. 

### Download project
* `sudo apt install git`
* `cd ~/`
* `git clone --depth 1 https://github.com/sgjava/javauio2.git`

### Run script
* `cd ~/javauio2/scripts`
* `./install-java.sh`
* `./setup-permissions.sh` (only required on ARM)
* `sudo reboot`
* `cd ~/javauio2`
* `mvn clean install` (X86_64 only)
* `-P arm64` (profile for aarch64)
* `-P arm32` (profile for armhf, but no runtime linker yet)

### Performance Benchmark (Pine64 ARM64)
In raw performance testing on the Pine A64 (Cortex-A53), the FFM implementation demonstrated a massive leap over established JNI methods:

| Operation | HawtJNI (Legacy) | **Java UIO 2 (FFM)** | **Improvement** |
| :--- | :--- | :--- | :--- |
| **GPIO Writes** | ~292k ops/sec | **~561k ops/sec** | **+91.5%** |
| **GPIO Reads** | ~400k ops/sec | **~582k ops/sec** | **+45.5%** |

*Note: Benchmarks were performed on single-core execution. With FFM, the Java-to-Native bridge is no longer the bottleneck; the performance limit is now defined by the Linux Kernel's ioctl latency.*

## 🛠️ Key Innovations

### 1. Hardware-Accurate Size Probing
Unlike other libraries that "guess" the size of C structs or use hardcoded offsets, Java UIO 2 uses a unique **Native Sizer** during the build process:
* **Cross-Architecture Probing**: Uses cross-compilers and QEMU User Emulation to probe the exact memory layout of the target architecture (ARM64, ARM32, X86_64).
* **Byte-Perfect Alignment**: Ensures that the MemoryLayout used in Java is identical to the C struct compiled for the target, preventing SIGSEGV and alignment issues common in cross-platform development.

### 2. Streamlined Cross-Compilation
Java UIO 2 is engineered for a modern dev-ops workflow. You can compile native code and generate Java bindings for ARM64 and ARM32 directly from an X86_64 workstation.
* Integrated **jextract** automation.
* Automated native library bundling for multiple architectures.
* Seamless integration with **Project Lombok** for clean, boilerplate-free code.

### 3. Native Character Device (CDEV) Support
Java UIO 2 prioritizes the modern Linux GPIO Character Device API over the deprecated sysfs interface. This provides:
* Better performance and reduced CPU overhead.
* Precise event timestamping.
* Atomic multi-line configuration.

## 🌍 Architecture Support Matrix (JDK 25)

Java UIO 2 provides a forward-looking strategy. While FFM is our primary focus for performance, we acknowledge the current state of 32-bit tooling.

| Architecture | JNI (Java UIO) | **FFM (Java UIO 2)** |
| :--- | :---: | :---: |
| **ARM32 (v7)** | ✅ Supported | ⏳ Pending Linker |
| **ARM64 (v8)** | ✅ Supported | ✅ **Recommended** |
| **X86_64** | ✅ Supported | ✅ **Recommended** |

*Note: As of JDK 25, X86_32 is no longer supported. Java UIO 2 focuses on high-performance 64-bit paths while maintaining a path for ARM32.*
