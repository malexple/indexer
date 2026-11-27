package ru.mcs.mcpsql.bot;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** At most {@code max} actions per user within the window. */
final class Throttle {

    private final int max;
    private final Duration window;
    private final Map<Long, ArrayDeque<Instant>> hits = new HashMap<>();

    Throttle(int max, Duration window) {
        this.max = max;
        this.window = window;
    }

    synchronized boolean allow(long user, Instant now) {
        ArrayDeque<Instant> queue = hits.computeIfAbsent(user, k -> new ArrayDeque<>());
        while (!queue.isEmpty() && !queue.peekFirst().plus(window).isAfter(now)) {
            queue.pollFirst();
        }
        if (queue.size() >= max) {
            return false;
        }
        queue.addLast(now);
        return true;
    }
}
