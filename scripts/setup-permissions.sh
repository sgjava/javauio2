#!/bin/bash
#
# Created on February 1, 2026
#
# @author: sgoldsmith
#
# Sets up non-root access for GPIO, I2C, SPI, PWM, and MMIO.
# Consolidated from legacy 98-sysfs.rules, 99-pwm.rules, and uio-permissions.sh.
#
# Steven P. Goldsmith
# sgjava@gmail.com

set -e

echo "--- Setting up Java UIO 2 Permissions ---"

# 1. Group Setup
sudo groupadd -f uio
sudo usermod -a -G uio "$USER"
sudo usermod -a -G dialout "$USER"

# 2. Create the Permissions Script
echo "Creating /usr/local/bin/uio-permissions.sh..."
sudo tee /usr/local/bin/uio-permissions.sh > /dev/null <<'EOF'
#!/bin/sh
# Set permissions to uio group for device nodes
chown -R root:uio /dev/mem /dev/gpiochip* /dev/i2c* /dev/spidev*
chmod -R ug+rw /dev/mem /dev/gpiochip* /dev/i2c* /dev/spidev*

# Set permissions for LED paths
if [ -d /sys/devices/platform/leds/leds ]; then
    chown -R root:uio /sys/devices/platform/leds/leds
    chmod -R ug+rw /sys/devices/platform/leds/leds
fi

# Allwinner SoC (sunxi) pinctrl and pwm logic from 98-sysfs and 99-pwm rules
chown -R root:uio /sys/class/gpio /sys/class/pwm
chmod -R g+w /sys/class/gpio /sys/class/pwm

# Target specific SoC platform device paths
chown -R root:uio /sys/devices/platform/soc/*.pinctrl /sys/devices/platform/soc/*.pwm/pwm/pwmchip* 2>/dev/null || true
chmod -R g+w /sys/devices/platform/soc/*.pinctrl /sys/devices/platform/soc/*.pwm/pwm/pwmchip* 2>/dev/null || true
exit 0
EOF
sudo chmod +x /usr/local/bin/uio-permissions.sh

# 3. Create Systemd Service
echo "Creating uio-permissions.service..."
sudo tee /etc/systemd/system/uio-permissions.service > /dev/null <<EOT
[Unit]
Description=Java UIO 2 Permissions Service
After=multi-user.target

[Service]
Type=oneshot
ExecStart=/usr/local/bin/uio-permissions.sh
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
EOT

# 4. Enable and Start
echo "Applying changes and starting service..."
sudo systemctl daemon-reload
sudo systemctl enable uio-permissions.service
sudo systemctl start uio-permissions.service

echo "--- Setup Complete ---"
echo "MMIO access (/dev/mem) and GPIO access are now available to the uio group."
