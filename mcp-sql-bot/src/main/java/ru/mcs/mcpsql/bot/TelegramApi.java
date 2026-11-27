package ru.mcs.mcpsql.bot;

import java.io.IOException;
import java.util.List;

public interface TelegramApi {

    record Button(String text, String data) {
    }

    Updates getUpdates(long offset, int timeoutSeconds) throws IOException, InterruptedException;

    void sendMessage(long chatId, String html, List<List<Button>> keyboard) throws IOException;

    void answerCallback(String callbackId) throws IOException;
}
