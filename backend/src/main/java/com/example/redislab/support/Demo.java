package com.example.redislab.support;

import com.example.redislab.model.DemoResult;

import java.util.ArrayList;
import java.util.List;

/** Collects the command log and checks for one demo run, then builds the {@link DemoResult}. */
public final class Demo {

    private static final int MAX_COMMANDS = 60;

    private final String name;
    private final long startNanos = System.nanoTime();
    private final List<String> commands = new ArrayList<>();
    private final List<DemoResult.Check> checks = new ArrayList<>();
    private int omitted;

    private Demo(String name) {
        this.name = name;
    }

    public static Demo start(String name) {
        return new Demo(name);
    }

    /** Records a command for the UI log; long runs keep the first entries and count the rest. */
    public Demo cmd(String command) {
        if (commands.size() < MAX_COMMANDS) {
            commands.add(command);
        } else {
            omitted++;
        }
        return this;
    }

    public Demo check(String name, boolean passed, String detail) {
        checks.add(new DemoResult.Check(name, passed, detail));
        return this;
    }

    public DemoResult finish(Object data) {
        List<String> log = new ArrayList<>(commands);
        if (omitted > 0) {
            log.add("... " + omitted + " more commands");
        }
        return new DemoResult(name, log, data, checks, elapsedMs(startNanos));
    }

    /** Milliseconds since {@code startNanos}, rounded to two decimals. */
    public static double elapsedMs(long startNanos) {
        return Math.round((System.nanoTime() - startNanos) / 10_000.0) / 100.0;
    }
}
