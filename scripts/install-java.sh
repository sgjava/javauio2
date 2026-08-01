#!/bin/bash
#
# Updated for Ubuntu 26.04 LTS
#
# Install dependencies, JDK 25, and Multi-Arch SDL2 Cross-Compile Tooling.
#
# Steven P. Goldsmith
# sgjava@gmail.com

set -e

ARCH=$(uname -m)
UBUNTU_CODENAME=$(lsb_release -sc)
SDKMAN_DIR="$HOME/.sdkman"
JAVA_TMP="$HOME/.java_tmp"
JEXTRACT_SRC="$HOME/jextract"
JEXTRACT_BIN_DIR="$HOME/.jextract/bin"

# Helper function to prevent unattended-upgrades locks from stopping the script
wait_for_apt_lock() {
    sudo systemctl stop unattended-upgrades || true
    while sudo fuser /var/lib/dpkg/lock-frontend >/dev/null 2>&1 || sudo fuser /var/lib/apt/lists/lock >/dev/null 2>&1 ; do
        echo "Waiting for apt/dpkg lock..."
        sleep 2
    done
}

echo "--------------------------------------------------"
echo "STEP 1: System Prep & Tmp Dir"
echo "--------------------------------------------------"
wait_for_apt_lock
sudo apt update && sudo apt install -y curl zip unzip wget xz-utils git build-essential clang libclang-dev

mkdir -p "$JAVA_TMP"
chmod 777 "$JAVA_TMP"

echo "--------------------------------------------------"
echo "STEP 2: SDKMAN Setup"
echo "--------------------------------------------------"
export SDKMAN_DIR="$HOME/.sdkman"
if [[ ! -d "$SDKMAN_DIR" ]]; then
    curl -s "https://get.sdkman.io" | bash || true
fi
[[ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]] && source "$SDKMAN_DIR/bin/sdkman-init.sh"

echo "--------------------------------------------------"
echo "STEP 3: JDK Installation"
echo "--------------------------------------------------"
case $ARCH in
    armv7l|armv8l)
        JDK_DIR="$SDKMAN_DIR/candidates/java/25-arm32-local"
        if [ ! -d "$JDK_DIR" ]; then
            wget -q -O /tmp/jdk25.tar.xz "https://builds.shipilev.net/openjdk-jdk25/openjdk-jdk25-linux-arm32-hflt-server.tar.xz"
            mkdir -p "$JDK_DIR"
            tar -xJf /tmp/jdk25.tar.xz -C "$JDK_DIR" --strip-components=1
            sdk install java 25-arm32-local "$JDK_DIR"
        fi
        sdk default java 25-arm32-local
        ;;
    *)
        sdk install java 25-zulu || true
        sdk default java 25-zulu
        ;;
esac

echo "--------------------------------------------------"
echo "STEP 4: Multi-Arch & Dev Tooling (x86_64 Only)"
echo "--------------------------------------------------"
if [ "$ARCH" == "x86_64" ]; then
    echo "Configuring Multi-Arch Repositories for arm64/armhf..."
    
    # 1. Add architectures
    sudo dpkg --add-architecture arm64
    sudo dpkg --add-architecture armhf

    # 2. Scope existing default Ubuntu sources to amd64 only (DEB822 format)
    SOURCES_FILE="/etc/apt/sources.list.d/ubuntu.sources"
    if [ -f "$SOURCES_FILE" ]; then
        if ! grep -q "Architectures:" "$SOURCES_FILE"; then
            sudo sed -i '/^URIs:/i Architectures: amd64' "$SOURCES_FILE"
        else
            sudo sed -i 's/^Architectures:.*/Architectures: amd64/' "$SOURCES_FILE"
        fi
    fi

    # Handle standard sources list if present
    if [ -f /etc/apt/sources.list ]; then
        sudo sed -i 's/^deb http/deb [arch=amd64] http/g' /etc/apt/sources.list
        sudo sed -i 's/^deb-src http/deb-src [arch=amd64] http/g' /etc/apt/sources.list
    fi
    
    # 3. Add the Ports mirrors for ARM architectures
    sudo tee /etc/apt/sources.list.d/arm-ports.list <<EOF
deb [arch=arm64,armhf] http://ports.ubuntu.com/ubuntu-ports/ $UBUNTU_CODENAME main restricted universe multiverse
deb [arch=arm64,armhf] http://ports.ubuntu.com/ubuntu-ports/ $UBUNTU_CODENAME-updates main restricted universe multiverse
deb [arch=arm64,armhf] http://ports.ubuntu.com/ubuntu-ports/ $UBUNTU_CODENAME-security main restricted universe multiverse
EOF

    wait_for_apt_lock
    sudo apt update
    
    # 4. Install Cross Compilers, target libs, and binfmt replacement for qemu-user-static
    sudo apt install -y libclang-dev llvm gcc-arm-linux-gnueabihf gcc-aarch64-linux-gnu cmake \
                        qemu-user qemu-user-binfmt \
                        libsdl2-dev:amd64 libsdl2-dev:arm64 libsdl2-dev:armhf

    sdk install maven || true
    sdk install ant || true
    sdk install gradle 9.3.0 || true
    sdk default gradle 9.3.0

    echo "Updating/Building jextract..."
    if [ ! -d "$JEXTRACT_SRC" ]; then
        git clone https://github.com/openjdk/jextract.git "$JEXTRACT_SRC"
    else
        cd "$JEXTRACT_SRC" && git pull
    fi

    cd "$JEXTRACT_SRC"
    rm -f gradlew gradlew.bat
    rm -rf gradle/wrapper

    LLVM_BASE_PATH=$(ls -d /usr/lib/llvm-* | sort -V | tail -n 1)
    LLVM_LIB_PATH="$LLVM_BASE_PATH/lib"
    
    ACTUAL_CLANG_SO=$(ls $LLVM_LIB_PATH/libclang-[0-9]*.so | head -n 1)
    if [ -n "$ACTUAL_CLANG_SO" ]; then
        echo "Repairing libclang symlink: $ACTUAL_CLANG_SO -> $LLVM_LIB_PATH/libclang.so"
        sudo ln -sf "$ACTUAL_CLANG_SO" "$LLVM_LIB_PATH/libclang.so"
        sudo ln -sf "$ACTUAL_CLANG_SO" "$LLVM_LIB_PATH/libclang.so.1"
    fi

    REAL_JAVA_HOME=$(readlink -f "$SDKMAN_DIR/candidates/java/current")

    gradle -Dorg.gradle.java.home="$REAL_JAVA_HOME" \
           -Pjdk_home="$REAL_JAVA_HOME" \
           -Pllvm_home="$LLVM_BASE_PATH" \
           clean compileJava processResources jar

    cp -r build/resources/main/* build/classes/java/main/ 2>/dev/null || true
    
    mkdir -p "$JEXTRACT_BIN_DIR"
    cat <<EOF > "$JEXTRACT_BIN_DIR/jextract"
#!/bin/bash
java --add-modules jdk.compiler \\
     --module-path "$JEXTRACT_SRC/build/classes/java/main" \\
     --add-modules org.openjdk.jextract --enable-native-access=org.openjdk.jextract \\
     -Djdk.library.path="$LLVM_LIB_PATH" \\
     -m org.openjdk.jextract/org.openjdk.jextract.JextractTool "\$@"
EOF
    chmod +x "$JEXTRACT_BIN_DIR/jextract"
fi

echo "--------------------------------------------------"
echo "STEP 5: Global Environment Persistence"
echo "--------------------------------------------------"
update_env_var() {
    local var_name=$1
    local var_value=$2
    if grep -q "^${var_name}=" /etc/environment; then
        sudo sed -i "s|^${var_name}=.*|${var_name}=\"${var_value}\"|" /etc/environment
    else
        echo "${var_name}=\"${var_value}\"" | sudo tee -a /etc/environment
    fi
}

JAVA_P="$SDKMAN_DIR/candidates/java/current"
M2_P="$SDKMAN_DIR/candidates/maven/current"
ANT_P="$SDKMAN_DIR/candidates/ant/current"
GRADLE_P="$SDKMAN_DIR/candidates/gradle/current"

update_env_var "JAVA_HOME" "$JAVA_P"
update_env_var "JAVA_OPTS" "-Djava.io.tmpdir=$JAVA_TMP"

if [ "$ARCH" == "x86_64" ]; then
    NEW_PATH="$JEXTRACT_BIN_DIR:$JAVA_P/bin:$M2_P/bin:$ANT_P/bin:$GRADLE_P/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    update_env_var "PATH" "$NEW_PATH"
    update_env_var "M2_HOME" "$M2_P"
    update_env_var "ANT_HOME" "$ANT_P"
    update_env_var "GRADLE_HOME" "$GRADLE_P"
else
    update_env_var "PATH" "$JAVA_P/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
fi

echo "--------------------------------------------------"
echo "STEP 6: Comprehensive Verification"
echo "--------------------------------------------------"
[[ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]] && source "$SDKMAN_DIR/bin/sdkman-init.sh"

printf "Java:      " && java -version 2>&1 | head -n 1

if [ "$ARCH" == "x86_64" ]; then
    printf "Maven:     " && mvn -version | head -n 1
    printf "Ant:       " && ant -version | head -n 1
    printf "Gradle:    " && gradle -version | grep "Gradle"
    printf "jextract:  " && "$JEXTRACT_BIN_DIR/jextract" --help | head -n 1
    
    # Multi-arch lib verification
    echo "Verifying SDL2 multi-arch libs..."
    [ -f /usr/lib/aarch64-linux-gnu/libSDL2.so ] && echo "SDL2 ARM64: OK" || echo "SDL2 ARM64: MISSING"
    [ -f /usr/lib/arm-linux-gnueabihf/libSDL2.so ] && echo "SDL2 ARM32: OK" || echo "SDL2 ARM32: MISSING"
fi
echo "--------------------------------------------------"
echo "Setup Complete! Please run: source /etc/environment"
echo "--------------------------------------------------"
