package ru.mcs.mcpsql.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * The whole conversation. Text commands and buttons map to the same actions. The Telegram user id is the
 * identity on the server; the bot keeps no state and never stores keys.
 */
@Component
public class BotHandler {

    private static final Logger LOG = LoggerFactory.getLogger(BotHandler.class);

    private final TelegramApi api;
    private final ServiceClient service;
    private final BotProperties properties;
    private final Supplier<Instant> clock;
    private final Throttle throttle = new Throttle(12, Duration.ofMinutes(1));

    @Autowired
    public BotHandler(TelegramApi api, ServiceClient service, BotProperties properties) {
        this(api, service, properties, Instant::now);
    }

    BotHandler(TelegramApi api, ServiceClient service, BotProperties properties, Supplier<Instant> clock) {
        this.api = api;
        this.service = service;
        this.properties = properties;
        this.clock = clock;
    }

    public void handle(Update update) {
        if (!update.privateChat()) {
            return;
        }
        if (update.callbackId() != null) {
            try {
                api.answerCallback(update.callbackId());
            } catch (IOException e) {
                LOG.debug("answerCallback failed: {}", e.getMessage());
            }
        }
        if (!throttle.allow(update.userId(), clock.get())) {
            return;
        }
        String action = update.callbackData() != null ? update.callbackData() : commandOf(update.text());
        long chat = update.chatId();
        long user = update.userId();
        switch (action) {
            case "start", "menu" -> send(chat, Texts.START, mainMenu());
            case "help" -> send(chat, Texts.HELP, mainMenu());
            case "keys" -> send(chat, Texts.CHOOSE_TTL, ttlMenu());
            case "me" -> me(chat, user);
            case "stats" -> stats(chat);
            case "reset" -> send(chat, Texts.CONFIRM_RESET, List.of(List.of(
                    new TelegramApi.Button("Да, удалить всё", "reset:yes"),
                    new TelegramApi.Button("Отмена", "reset:no"))));
            case "reset:yes" -> reset(chat, user);
            case "reset:no" -> send(chat, Texts.RESET_CANCELLED, mainMenu());
            default -> {
                if (action.startsWith("issue:") && Texts.ttls().contains(action.substring(6))) {
                    issue(chat, user, action.substring(6));
                } else {
                    send(chat, Texts.UNKNOWN, mainMenu());
                }
            }
        }
    }

    private void issue(long chat, long user, String ttl) {
        try {
            ServiceClient.IssuedKeys keys = service.issueKeys(user, ttl);
            send(chat, Texts.keys(keys, properties.publicUrl()), mainMenu());
        } catch (ServiceException e) {
            send(chat, Texts.issueError(e), mainMenu());
        }
    }

    private void me(long chat, long user) {
        try {
            send(chat, Texts.me(service.me(user)), mainMenu());
        } catch (ServiceException e) {
            send(chat, Texts.unavailable(), mainMenu());
        }
    }

    private void stats(long chat) {
        try {
            send(chat, Texts.stats(service.stats()), mainMenu());
        } catch (ServiceException e) {
            send(chat, Texts.unavailable(), mainMenu());
        }
    }

    private void reset(long chat, long user) {
        try {
            send(chat, Texts.resetDone(service.reset(user)), mainMenu());
        } catch (ServiceException e) {
            send(chat, Texts.unavailable() + " Отзыв ключей не выполнен, повторите позже.", mainMenu());
        }
    }

    private void send(long chat, String html, List<List<TelegramApi.Button>> keyboard) {
        try {
            api.sendMessage(chat, html, keyboard);
        } catch (IOException e) {
            LOG.warn("cannot send a message: {}", e.getMessage());
        }
    }

    static String commandOf(String text) {
        if (text == null) {
            return "";
        }
        String first = text.strip().split("\\s+", 2)[0];
        if (!first.startsWith("/")) {
            return "";
        }
        int at = first.indexOf('@');
        return (at > 0 ? first.substring(1, at) : first.substring(1)).toLowerCase(java.util.Locale.ROOT);
    }

    private static List<List<TelegramApi.Button>> mainMenu() {
        return List.of(
                List.of(new TelegramApi.Button("Получить ключи", "keys"), new TelegramApi.Button("Мои данные", "me")),
                List.of(new TelegramApi.Button("Статистика", "stats"), new TelegramApi.Button("Помощь", "help")),
                List.of(new TelegramApi.Button("Удалить всё и отозвать ключи", "reset")));
    }

    private static List<List<TelegramApi.Button>> ttlMenu() {
        return List.of(
                List.of(new TelegramApi.Button("5 часов", "issue:5h"), new TelegramApi.Button("Сутки", "issue:1d"),
                        new TelegramApi.Button("Неделя", "issue:7d")),
                List.of(new TelegramApi.Button("Назад", "menu")));
    }
}
