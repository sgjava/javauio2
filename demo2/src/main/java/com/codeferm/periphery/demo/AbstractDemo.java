/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;

/**
 * Base for all demos.
 *
 * @author Steven P. Goldsmith
 * @version 1.1.0
 */
@Slf4j
public abstract class AbstractDemo implements Callable<Integer> {

    static {
        NativeLoader.load();
    }

    /**
     * Common terminal cleanup hook.
     */
    protected final void addTerminalHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            System.out.flush();
        }));
    }

    /**
     * Subclasses must implement the Picocli call method.
     *
     * * @return Exit code.
     * @throws Exception On hardware or execution error.
     */
    @Override
    public abstract Integer call() throws Exception;
}
