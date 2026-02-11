/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2.demo;

import java.lang.foreign.MemorySegment;
import lombok.extern.slf4j.Slf4j;
import org.u8g2.U8g2;
import picocli.CommandLine;

/**
 * Simple text demo.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@CommandLine.Command(name = "SimpleText", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Simple text demo")
public class SimpleText extends Base {

    @Override
    protected void run(MemorySegment u8g2) {
        // You can use showText from the base class
        showText(u8g2, "Welcome to Java 25 FFM!");
        // Or do custom drawing using the instance arena
        U8g2.u8g2_DrawCircle(u8g2, (short) 64, (short) 32, (short) 10, (byte) 1);
        U8g2.u8g2_SendBuffer(u8g2);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SimpleText()).execute(args);
        System.exit(exitCode);
    }

}
