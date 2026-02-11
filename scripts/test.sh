
#!/bin/bash
#
# Created: February 1, 2026
# @author: sgoldsmith
#
# Automates c-periphery C build, Jextract FFM binding, and Maven packaging.

set -e

# Project configuration
PROJ_DIR="$HOME/test/periphery-java"
C_SRC_DIR="$HOME/test/c-periphery"
PACKAGE="org.periphery"
TARGET_JAR="periphery-2.5.0.jar"

# Resolve LLVM for jextract
LLVM_PATH=$(ls -d /usr/lib/llvm-* | sort -V | tail -n 1)/lib
export LD_LIBRARY_PATH=$LLVM_PATH

echo "--------------------------------------------------"
echo "STEP 1: Build Native C-Periphery"
echo "--------------------------------------------------"
mkdir -p "$HOME/test"
cd "$HOME/test"

if [ ! -d "$C_SRC_DIR" ]; then
    git clone https://github.com/vsergeev/c-periphery.git
fi

cd "$C_SRC_DIR"
mkdir -p build && cd build
cmake -DBUILD_SHARED_LIBS=ON ..
make -j$(nproc)

echo "--------------------------------------------------"
echo "STEP 2: Initialize Maven Project Structure"
echo "--------------------------------------------------"
mkdir -p "$PROJ_DIR/src/main/java"
mkdir -p "$PROJ_DIR/src/test/java"
cd "$PROJ_DIR"

# Create pom.xml
cat <<EOF > pom.xml
<project xmlns="http://maven.apache.org/POM/4.0.0" 
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>org.periphery</groupId>
    <artifactId>periphery-java</artifactId>
    <version>2.5.0</version>
    <properties>
        <maven.compiler.source>25</maven.compiler.source>
        <maven.compiler.target>25</maven.compiler.target>
    </properties>
</project>
EOF

echo "--------------------------------------------------"
echo "STEP 3: Generate FFM Bindings with jextract"
echo "--------------------------------------------------"
# Create wrapper.h including all public headers
ls "$C_SRC_DIR/src/"*.h | grep -v "_internal.h" | sed 's|.*|#include "&"|' > wrapper.h

# Phase 1: Dump includes to filter
jextract --header-class-name Periphery --dump-includes includes.txt wrapper.h

# Phase 2: Filter for periphery headers specifically
grep "c-periphery/src/" includes.txt | grep -v "_handle" | grep -v "_ops" | grep -v "unnamed" > filtered_includes.txt

# Phase 3: Generate Java Source
jextract --output src/main/java \
         --target-package $PACKAGE \
         --header-class-name Periphery \
         -I . \
         -l periphery \
         @filtered_includes.txt wrapper.h

echo "--------------------------------------------------"
echo "STEP 4: Compile Maven Artifact"
echo "--------------------------------------------------"
mvn clean package

echo "--------------------------------------------------"
echo "STEP 5: Create and Run Version Test"
echo "--------------------------------------------------"
cat <<EOF > src/test/java/VersionTest.java
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.periphery.Periphery;

public class VersionTest {
    public static void main(String[] args) {
        try (Arena arena = Arena.ofConfined()) {
            System.out.println("========================================");
            System.out.println("   C-PERIPHERY JAVA BINDING TEST");
            System.out.println("========================================");

            MemorySegment versionPtr = Periphery.periphery_version_info();
            
            if (versionPtr.equals(MemorySegment.NULL)) {
                System.err.println("Error: Could not retrieve version string.");
            } else {
                String fullVersion = versionPtr.reinterpret(1024).getString(0);
                System.out.println("Full Version String : " + fullVersion);
            }

            int major = Periphery.PERIPHERY_VERSION_MAJOR();
            int minor = Periphery.PERIPHERY_VERSION_MINOR();
            int patch = Periphery.PERIPHERY_VERSION_PATCH();
            System.out.printf("Semantic Version    : %d.%d.%d\n", major, minor, patch);

            System.out.println("========================================");
            System.out.println("Native library link: SUCCESS");
        } catch (Exception e) {
            System.err.println("Fatal Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
EOF

# Compile the test using the newly created JAR
javac -cp target/periphery-java-2.5.0.jar src/test/java/VersionTest.java

# Run the test
# Note: We point LD_LIBRARY_PATH to the C build folder so Java can find libperiphery.so
LD_LIBRARY_PATH="$C_SRC_DIR/build" java -cp "src/test/java:target/periphery-java-2.5.0.jar" \
    --enable-native-access=ALL-UNNAMED VersionTest
