package ru.mcs.mcpsql.bot;

/**
 * What the bot cares about in a Telegram update: a text message or a button press.
 * For a button press {@code callbackId} and {@code callbackData} are set and {@code text} is null.
 */
public record Update(long updateId, long chatId, boolean privateChat, long userId, String text,
                     String callbackId, String callbackData) {
}
