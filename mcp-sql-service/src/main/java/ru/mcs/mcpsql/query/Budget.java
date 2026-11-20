package ru.mcs.mcpsql.query;

/** Collects an answer line by line and stops at the character budget, marking the cut. */
final class Budget {

    private final StringBuilder out = new StringBuilder();
    private final int max;
    private boolean truncated;

    Budget(int max) {
        this.max = max;
    }

    boolean line(String text) {
        if (truncated) {
            return false;
        }
        if (out.length() + text.length() + 1 > max) {
            truncated = true;
            return false;
        }
        out.append(text).append('\n');
        return true;
    }

    String finish() {
        if (truncated) {
            out.append("... [truncated at ").append(max).append(" chars; narrow the query or ask for a smaller range]\n");
        }
        return out.toString();
    }
}