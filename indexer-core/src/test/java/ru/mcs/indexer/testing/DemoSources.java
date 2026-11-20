package ru.mcs.indexer.testing;

public final class DemoSources {

    private DemoSources() {
    }

    /** 24 lines; line numbers are asserted by tests. */
    public static final String MANAGER = String.join("\n",
            "package demo.core;",
            "",
            "import java.util.List;",
            "import static java.util.Objects.requireNonNull;",
            "import java.io.*;",
            "",
            "/**",
            " * Manages editors. Second sentence.",
            " */",
            "@Deprecated(since = \"1\")",
            "public class Manager<T> extends Base implements Runnable, Comparable<Manager<T>> {",
            "    private final List<String> names = null, other = null;",
            "    /** Opens it. */",
            "    @Override",
            "    public void run() {}",
            "    public Manager(int a, String... rest) {}",
            "    static { }",
            "    public <R> R open(Map.Entry<String, R> e, int[] arr, boolean f) { return null; }",
            "    public static class Inner { void x() {} }",
            "    enum Mode implements Runnable { A, B; public void run() {} }",
            "}",
            "sealed interface Shape permits Circle, Square {}",
            "record Circle(double r, @Deprecated String label) implements Shape { Circle { } }",
            "@interface Marker { String value() default \"\"; }",
            "");
}