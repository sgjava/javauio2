#!/bin/bash

PWMCHIP=/sys/class/pwm/pwmchip0
PWM=$PWMCHIP/pwm0
PERIOD=1000000

# Number of export/unexport cycles to test
CYCLES=3

for cycle in $(seq 1 "$CYCLES"); do
    echo
    echo "========== PWM cycle $cycle/$CYCLES =========="

    # --------------------------------------------------
    # Export PWM channel
    # --------------------------------------------------

    if [ ! -d "$PWM" ]; then
        echo 0 > "$PWMCHIP/export"

        # Wait for udev to restore uio group permissions.
        # Don't rely on an arbitrary sleep.
        for i in {1..100}; do
            if [ -w "$PWM/period" ] &&
               [ -w "$PWM/duty_cycle" ] &&
               [ -w "$PWM/enable" ]; then
                break
            fi

            sleep 0.01
        done
    fi

    # --------------------------------------------------
    # Verify PWM permissions
    # --------------------------------------------------

    echo "PWM permissions:"
    ls -l "$PWM"/{period,duty_cycle,enable}

    if ! [ -w "$PWM/period" ] ||
       ! [ -w "$PWM/duty_cycle" ] ||
       ! [ -w "$PWM/enable" ]; then

        echo
        echo "ERROR: PWM permissions were not established by udev."
        echo "Giving up on this cycle."
        exit 1
    fi

    echo "PWM attributes are writable."

    # --------------------------------------------------
    # Configure PWM
    # --------------------------------------------------

    echo "$PERIOD" > "$PWM/period"

    # --------------------------------------------------
    # Enable PWM
    # --------------------------------------------------

    echo 1 > "$PWM/enable"

    # --------------------------------------------------
    # Sweep brightness from 10% to 100%
    # --------------------------------------------------

    for brightness in {10..100..10}; do

        # Active-low backlight:
        #
        # 10% brightness  = 90% duty
        # 20% brightness  = 80% duty
        # ...
        # 100% brightness = 0% duty
        #
        duty=$((PERIOD * (100 - brightness) / 100))

        echo "Brightness: ${brightness}% (Duty cycle: $duty)"

        echo "$duty" > "$PWM/duty_cycle"

        sleep 0.3
    done

    # --------------------------------------------------
    # Turn backlight completely OFF
    # --------------------------------------------------

    # Active-low:
    # 100% duty cycle = backlight OFF
    echo "$PERIOD" > "$PWM/duty_cycle"

    echo "Backlight turned off."

    # --------------------------------------------------
    # Disable PWM before unexport
    # --------------------------------------------------

    echo 0 > "$PWM/enable"

    # --------------------------------------------------
    # Unexport PWM
    # --------------------------------------------------

    echo 0 > "$PWMCHIP/unexport"

    echo "PWM unexported."

    sleep 0.5
done

echo
echo "========== Test complete =========="
