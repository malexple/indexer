package ru.mcs.indexer.util;

import java.io.PrintStream;

public final class Log {

    private final boolean verbose;
    private final PrintStream out;
    private final PrintStream err;

    public Log(boolean verbose) {
        this(verbose, System.out, System.err);
    }

    public Log(boolean verbose, PrintStream out, PrintStream err) {
        this.verbose = verbose;
        this.out = out;
        this.err = err;
    }

    public void info(String message) {
        out.println(message);
    }

    public void debug(String message) {
        if (verbose) {
            out.println("[debug] " + message);
        }
    }

    public void warn(String message) {
        err.println("warning: " + message);
    }

    public void error(String message) {
        err.println(message);
    }
}