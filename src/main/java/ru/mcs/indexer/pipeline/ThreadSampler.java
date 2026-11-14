package ru.mcs.indexer.pipeline;

import ru.mcs.indexer.util.Log;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Lightweight sampling profiler for worker threads, enabled with -Dindexer.profile=true. */
final class ThreadSampler implements AutoCloseable {

    private static final int INTERVAL_MS = 50;
    private static final int TOP = 8;

    private final Log log;
    private final String prefix;
    private final Map<String, Integer> byState = new TreeMap<>();
    private final Map<String, Integer> blocked = new HashMap<>();
    private final Map<String, Integer> topFrames = new HashMap<>();
    private final Map<String, Integer> parserFrames = new HashMap<>();
    private int workerSamples;
    private volatile boolean running = true;
    private final Thread thread;

    ThreadSampler(String prefix, Log log) {
        this.prefix = prefix;
        this.log = log;
        this.thread = new Thread(this::loop, "indexer-sampler");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void loop() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        while (running) {
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            for (ThreadInfo info : mx.getThreadInfo(mx.getAllThreadIds(), 24)) {
                if (info == null || !info.getThreadName().startsWith(prefix)) {
                    continue;
                }
                workerSamples++;
                byState.merge(info.getThreadState().name(), 1, Integer::sum);
                if (info.getThreadState() == Thread.State.BLOCKED) {
                    blocked.merge(String.valueOf(info.getLockName()), 1, Integer::sum);
                }
                StackTraceElement[] stack = info.getStackTrace();
                if (stack.length > 0) {
                    topFrames.merge(stack[0].getClassName() + "." + stack[0].getMethodName(), 1, Integer::sum);
                }
                for (StackTraceElement element : stack) {
                    if (element.getClassName().startsWith("com.github.javaparser")) {
                        parserFrames.merge(element.getClassName() + "." + element.getMethodName(), 1, Integer::sum);
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("[profile] worker samples=" + workerSamples + ", interval=" + INTERVAL_MS + " ms");
        for (Map.Entry<String, Integer> entry : byState.entrySet()) {
            log.info("[profile] state " + entry.getKey() + " = " + percent(entry.getValue()));
        }
        print("blocked on monitor", blocked);
        print("top frame", topFrames);
        print("top javaparser frame", parserFrames);
    }

    private void print(String title, Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> sorted = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(TOP)
                .toList();
        for (Map.Entry<String, Integer> entry : sorted) {
            String key = entry.getKey();
            if (key.length() > 100) {
                key = key.substring(0, 100);
            }
            log.info("[profile] " + title + ": " + percent(entry.getValue()) + " " + key);
        }
    }

    private String percent(int count) {
        return workerSamples == 0 ? "0%" : (100 * count / workerSamples) + "%";
    }
}