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

# Initialize logfile variable to prevent unbound variable errors
logfile="/tmp/uio-setup.log"

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

# 5. Copy udev rules 
sudo cp 98-sysfs.rules /etc/udev/rules.d/. >> "$logfile" 2>&1
sudo cp 99-pwm.rules /etc/udev/rules.d/. >> "$logfile" 2>&1

echo "--- Setup Complete ---"
echo "MMIO access (/dev/mem) and GPIO access are now available to the uio group."
