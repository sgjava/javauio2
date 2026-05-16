/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.device.RotaryEncoder;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Interrupt-Driven Rotary Encoder Application Demo using Java FFM.
 * <p>
 * This application initializes the rotary encoder hardware layer and attaches native event tracking hooks to capture quadrature
 * turns and shaft switch transitions.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "RotaryEncoderDemo", mixinStandardHelpOptions = true, version = "2.1.2")
public class RotaryEncoderDemo extends AbstractDemo {

    /**
     * GPIO character chip path.
     */
    @Option(names = {"-d", "--device"}, description = "GPIO character chip path.", defaultValue = "/dev/gpiochip0")
    private String device;

    /**
     * GPIO line index for CLK.
     */
    @Option(names = {"-c", "--clk"}, description = "GPIO line index for CLK.", defaultValue = "22")
    private int clkLine;

    /**
     * GPIO line index for DT.
     */
    @Option(names = {"-t", "--dt"}, description = "GPIO line index for DT.", defaultValue = "27")
    private int dtLine;

    /**
     * GPIO line index for SW.
     */
    @Option(names = {"-w", "--sw"}, description = "GPIO line index for SW.", defaultValue = "17")
    private int swLine;

    /**
     * Debounce filtering threshold in ms.
     */
    @Option(names = {"-b", "--debounce"}, description = "Debounce filtering threshold in ms.", defaultValue = "40")
    private long debounceMs;

    /**
     * Total duration context runtime in seconds.
     */
    @Option(names = {"-s", "--seconds"}, description = "Total duration context runtime in seconds.", defaultValue = "45")
    private int durationSeconds;

    /**
     * Coordinates the application execution lifecycle.
     *
     * @return 0 on successful processing, 1 on application failure.
     * @throws Exception On hardware or execution error.
     */
    @Override
    public Integer call() throws Exception {
        addTerminalHook();
        log.info("Starting Edge Interrupt Rotary Demo [CLK: {}, DT: {}, SW: {}]", this.clkLine, this.dtLine, this.swLine);

        final var position = new AtomicInteger(0);

        try (final var encoder = new RotaryEncoder(this.device, this.clkLine, this.dtLine, this.swLine)) {

            // Watch expects exactly 3 arguments: debounceMs, rotationAction, buttonAction
            encoder.watch(this.debounceMs,
                    (final Integer direction) -> {
                final var currentPos = position.addAndGet(direction);
                if (direction > 0) {
                    log.info("[Pos: {}] CW (+)", currentPos);
                } else {
                    log.info("[Pos: {}] CCW (-)", currentPos);
                }
            },
                    (final Integer edge, final Long timestamp) -> {
                if (null == edge) {
                    log.debug("Unknown button edge state registered: {}", edge);
                } else {
                    switch (edge) {
                        case 2 ->
                            log.info("[BUTTON EVENT] Shaft Pressed Down (Falling Edge) | TS: {}", timestamp);
                        case 1 ->
                            log.info("[BUTTON EVENT] Shaft Released Up (Rising Edge) | TS: {}", timestamp);
                        default ->
                            log.debug("Unknown button edge state registered: {}", edge);
                    }
                }
            }
            );

            TimeUnit.SECONDS.sleep(this.durationSeconds);
            log.info("Demo session window closed. Final registered step position: {}", position.get());
            return 0;
        } catch (final InterruptedException e) {
            log.error("Execution loop interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return 1;
        } catch (final Exception e) {
            log.error("Critical application failure: {}", e.getMessage());
            return 1;
        }
    }

    /**
     * Entry point command-line interface proxy.
     *
     * @param args Command line execution runtime arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new RotaryEncoderDemo()).execute(args));
    }
}
