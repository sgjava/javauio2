#!/bin/bash
#
# Created on February 1, 2026
#
# @author: sgoldsmith
#
# Sets up non-root access for GPIO, I2C, SPI, PWM, and MMIO.
#
# Steven P. Goldsmith
# sgjava@gmail.com

set -e

logfile="/tmp/uio-setup.log"

echo "--- Setting up Java UIO 2 Permissions ---"

# --------------------------------------------------
# 1. Group Setup
# --------------------------------------------------

sudo groupadd -f uio
sudo usermod -a -G uio "$USER"
sudo usermod -a -G dialout "$USER"

# --------------------------------------------------
# 2. Create the Permissions Script
# --------------------------------------------------

echo "Creating /usr/local/bin/uio-permissions.sh..."

sudo tee /usr/local/bin/uio-permissions.sh > /dev/null <<'EOF'
#!/bin/sh

# Set permissions for device nodes safely with glob checks
for dev in /dev/mem /dev/gpiochip* /dev/i2c* /dev/spidev*; do
    if [ -e "$dev" ]; then
        chown root:uio "$dev"
        chmod ug+rw "$dev"
    fi
done

# Set permissions for LED sysfs paths
if [ -d /sys/devices/platform/leds/leds ]; then
    chown -R root:uio /sys/devices/platform/leds/leds
    chmod -R ug+rw /sys/devices/platform/leds/leds
fi

exit 0
EOF

sudo chmod +x /usr/local/bin/uio-permissions.sh

# --------------------------------------------------
# 3. Probe PWM Availability
# --------------------------------------------------

has_pwm=false
if [ -d "/sys/class/pwm/pwmchip0" ]; then
    has_pwm=true
fi

# --------------------------------------------------
# 4. Install udev Rules & Optional Service
# --------------------------------------------------

echo "Installing udev rules..."

if [ -f "98-sysfs.rules" ]; then
    echo "Installing 98-sysfs.rules..."
    sudo cp 98-sysfs.rules /etc/udev/rules.d/
else
    echo "Error: 98-sysfs.rules not found!" >&2
    exit 1
fi

if [ "$has_pwm" = true ]; then
    echo "PWM detected. Setting up backlight service and rules..."

    if [ -f "pwm-backlight-off.service" ]; then
        sudo cp pwm-backlight-off.service /etc/systemd/system/
        sudo chmod 644 /etc/systemd/system/pwm-backlight-off.service
    else
        echo "Error: pwm-backlight-off.service not found!" >&2
        exit 1
    fi

    if [ -f "99-pwm0.rules" ]; then
        sudo cp 99-pwm0.rules /etc/udev/rules.d/
    else
        echo "Error: 99-pwm0.rules not found!" >&2
        exit 1
    fi
else
    echo "PWM not detected. Skipping backlight service installation."
fi

# --------------------------------------------------
# 5. Reload systemd and udev
# --------------------------------------------------

echo "Reloading systemd and udev..."

sudo systemctl daemon-reload
sudo udevadm control --reload-rules
sudo udevadm trigger

# --------------------------------------------------
# 6. Enable / Start Services Conditionally
# --------------------------------------------------

if [ "$has_pwm" = true ]; then
    echo "Enabling PWM backlight startup service..."
    sudo systemctl enable pwm-backlight-off.service
fi

# --------------------------------------------------
# 7. Apply UIO Permissions Now
# --------------------------------------------------

echo "Applying UIO permissions..."

sudo /usr/local/bin/uio-permissions.sh

if [ "$has_pwm" = true ]; then
    echo "Turning backlight off..."
    sudo systemctl restart pwm-backlight-off.service
fi

echo
echo "--- Setup Complete ---"
echo
echo "Configured:"
echo "  UIO group permissions"
echo "  GPIO permissions"
echo "  I2C permissions"
echo "  SPI permissions"
echo "  MMIO permissions"
echo "  LED sysfs permissions"
if [ "$has_pwm" = true ]; then
    echo "  PWM udev permissions"
    echo "  PWM backlight OFF at boot"
fi
