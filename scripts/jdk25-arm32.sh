#!/bin/bash
#
# Created on August 5, 2026
#
# @author: sgoldsmith
# ==============================================================================
# OpenJDK 25 Production Cross-Compilation Script (arm32 / armhf)
# ==============================================================================
# Builds an optimized, stripped, NEON-enabled OpenJDK 25 release image for
# Allwinner H2+ (NanoPi H2+), H3, and ARMv7-A devices. FFM enabled too.
# ==============================================================================

set -euo pipefail

# Environment constants
BUILD_DIR="${BUILD_DIR:-$HOME/jdk25-build}"
BOOT_JDK_DIR="$BUILD_DIR/boot-jdk24"
JDK_SRC_DIR="$BUILD_DIR/jdk25u"
DIST_DIR="$BUILD_DIR/dist"
TARGET_ARCH="arm-linux-gnueabihf"

# Compiler flags targeting Cortex-A7 with NEON + VFPv4 hard-float
CFLAGS_PROD="-O3 -mcpu=cortex-a7 -mfpu=neon-vfpv4 -mfloat-abi=hard"

echo "================================================================="
echo " Building Production OpenJDK 25 (arm32 / armhf + NEON)"
echo " Target: ARMv7-A (Cortex-A7 / H2+ / H3)"
echo "================================================================="

# -----------------------------------------------------------------------------
# STEP 1: System Dependencies (Host amd64 + Target armhf Multi-Arch)
# -----------------------------------------------------------------------------
echo "[1/6] Installing host & target cross-build dependencies..."

sudo dpkg --add-architecture armhf 2>/dev/null || true
CODENAME=$(lsb_release -cs 2>/dev/null || echo "resolute")

if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
    sudo bash -c "cat <<EOF > /etc/apt/sources.list.d/ubuntu.sources
Types: deb
URIs: http://us.archive.ubuntu.com/ubuntu/
Suites: ${CODENAME} ${CODENAME}-updates ${CODENAME}-backports
Components: main restricted universe multiverse
Architectures: amd64
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: http://security.ubuntu.com/ubuntu/
Suites: ${CODENAME}-security
Components: main restricted universe multiverse
Architectures: amd64
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF"
fi

if [ ! -f /etc/apt/sources.list.d/armhf-ports.sources ]; then
    sudo bash -c "cat <<EOF > /etc/apt/sources.list.d/armhf-ports.sources
Types: deb
URIs: http://ports.ubuntu.com/ubuntu-ports
Suites: ${CODENAME} ${CODENAME}-updates ${CODENAME}-security ${CODENAME}-backports
Components: main restricted universe multiverse
Architectures: armhf
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF"
fi

sudo apt-get update -qq

sudo apt-get install -y --no-install-recommends \
    build-essential autoconf git curl tar ca-certificates file pkg-config \
    gcc-arm-linux-gnueabihf g++-arm-linux-gnueabihf \
    zlib1g-dev zlib1g-dev:armhf \
    libffi-dev libffi-dev:armhf \
    libfontconfig1-dev libfontconfig1-dev:armhf \
    libfreetype6-dev libfreetype-dev:armhf \
    libasound2-dev:armhf libcups2-dev:armhf \
    libx11-dev:armhf libxext-dev:armhf libxrender-dev:armhf \
    libxrandr-dev:armhf libxtst-dev:armhf libxt-dev:armhf

# -----------------------------------------------------------------------------
# STEP 2: Boot JDK Setup
# -----------------------------------------------------------------------------
echo "[2/6] Setting up Boot JDK..."

mkdir -p "$BUILD_DIR"

boot_jdk_path=""
for candidate in "/usr/lib/jvm/java-25-openjdk-amd64" "/usr/lib/jvm/java-24-openjdk-amd64" "$BOOT_JDK_DIR"; do
    if [ -x "$candidate/bin/java" ]; then
        boot_jdk_path="$candidate"
        break
    fi
done

if [ -z "$boot_jdk_path" ]; then
    host_arch="$(uname -m)"
    jdk_arch="aarch64"
    if [ "$host_arch" = "x86_64" ]; then
        jdk_arch="x64"
    fi
    temp_tar="$BUILD_DIR/boot-jdk24.tar.gz"
    curl -sSLf "https://api.adoptium.net/v3/binary/latest/24/ga/linux/${jdk_arch}/jdk/hotspot/normal/eclipse" -o "$temp_tar"
    mkdir -p "$BOOT_JDK_DIR"
    tar -xzf "$temp_tar" -C "$BOOT_JDK_DIR" --strip-components=1
    rm -f "$temp_tar"
    boot_jdk_path="$BOOT_JDK_DIR"
fi

echo "Using Boot JDK at: $boot_jdk_path"

# -----------------------------------------------------------------------------
# STEP 3: Clone JDK Source & Patch
# -----------------------------------------------------------------------------
echo "[3/6] Syncing openjdk/jdk25u repository..."

if [ ! -d "$JDK_SRC_DIR/.git" ]; then
    git clone --depth 1 https://github.com/openjdk/jdk25u.git "$JDK_SRC_DIR"
else
    git -C "$JDK_SRC_DIR" fetch --depth 1 origin || true
    git -C "$JDK_SRC_DIR" reset --hard origin/master || git -C "$JDK_SRC_DIR" reset --hard origin/main
fi

GLOBAL_DEF_FILE="$JDK_SRC_DIR/src/hotspot/share/utilities/globalDefinitions.hpp"
if grep -q "static inline unsigned int uabs(int n)" "$GLOBAL_DEF_FILE"; then
    echo "Patching uabs linkage..."
    sed -i 's/static inline unsigned int uabs(int n) { return uabs((unsigned int)n); }/inline unsigned int uabs(int n) { return n < 0 ? -(unsigned int)n : (unsigned int)n; }/' "$GLOBAL_DEF_FILE"
fi

# -----------------------------------------------------------------------------
# STEP 4: Configure Production Build
# -----------------------------------------------------------------------------
echo "[4/6] Running production configure..."

cd "$JDK_SRC_DIR"
rm -rf build/

export PKG_CONFIG_PATH="/usr/lib/arm-linux-gnueabihf/pkgconfig"

bash configure \
    --openjdk-target="$TARGET_ARCH" \
    --with-debug-level=release \
    --with-native-debug-symbols=none \
    --enable-fallback-linker \
    --enable-headless-only \
    --with-jvm-variants=server \
    --with-copyright-year=2026 \
    --with-source-date=current \
    --with-extra-cflags="$CFLAGS_PROD" \
    --with-extra-cxxflags="$CFLAGS_PROD" \
    --disable-warnings-as-errors \
    --with-boot-jdk="$boot_jdk_path"

SPEC_FILE=$(find build/ -type f -name "spec.gmk" | head -n 1)
if [ -f "$SPEC_FILE" ]; then
    sed -i 's|^SOURCE_DATE_ISO_8601 :=.*|SOURCE_DATE_ISO_8601 := 2026-08-03T12:00:00Z|' "$SPEC_FILE"
fi

# -----------------------------------------------------------------------------
# STEP 5: Build Image, Strip Symbols & Create Archive
# -----------------------------------------------------------------------------
echo "[5/6] Building production JDK image..."
make CONF=linux-arm-server-release images JOBS="$(nproc)"

OUTPUT_JDK="$JDK_SRC_DIR/build/linux-arm-server-release/images/jdk"

if [ ! -x "$OUTPUT_JDK/bin/java" ]; then
    echo "ERROR: Build failed."
    exit 1
fi

echo "[5.1/6] Stripping native binaries and libraries..."
find "$OUTPUT_JDK" \( -type f -path "*/bin/*" -o -name "*.so" \) \
    -exec arm-linux-gnueabihf-strip --strip-unneeded {} + 2>/dev/null || true

echo "[6/6] Packaging tar.gz distribution archive..."
mkdir -p "$DIST_DIR"

TAR_NAME="openjdk-25-jdk_arm32-server-release-neon.tar.gz"
DIST_TAR="$DIST_DIR/$TAR_NAME"

tar -czf "$DIST_TAR" -C "$JDK_SRC_DIR/build/linux-arm-server-release/images" --transform 's/^jdk/jdk-25-arm32/' jdk

echo ""
echo "================================================================="
echo " BUILD SUCCESSFUL!"
echo "================================================================="
echo "JDK Image Path:  $OUTPUT_JDK"
echo "Distro Archive: $DIST_TAR"
echo "Archive Size:   $(du -h "$DIST_TAR" | cut -f1)"
echo "================================================================="
echo ""
echo "Running validation command:"
echo "file $OUTPUT_JDK/bin/java"
echo "-----------------------------------------------------------------"
file "$OUTPUT_JDK/bin/java"

