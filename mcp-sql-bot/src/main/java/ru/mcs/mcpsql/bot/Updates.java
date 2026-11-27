package ru.mcs.mcpsql.bot;

import java.util.List;

/** A batch of updates and the offset to ask for next, which also confirms this batch to Telegram. */
public record Updates(List<Update> items, long nextOffset) {
}
