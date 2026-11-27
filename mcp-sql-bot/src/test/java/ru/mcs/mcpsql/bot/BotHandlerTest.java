package ru.mcs.mcpsql.bot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BotHandlerTest {

    private record Sent(long chat, String html, List<List<TelegramApi.Button>> keyboard) {
    }

    private static final class FakeApi implements TelegramApi {
        final List<Sent> sent = new ArrayList<>();
        final List<String> answered = new ArrayList<>();

        @Override
        public Updates getUpdates(long offset, int timeoutSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void sendMessage(long chatId, String html, List<List<Button>> keyboard) {
            sent.add(new Sent(chatId, html, keyboard));
        }

        @Override
        public void answerCallback(String callbackId) {
            answered.add(callbackId);
        }
    }

    private static final class FakeService implements ServiceClient {
        final List<String> calls = new ArrayList<>();
        ServiceException failure;
        Me me = new Me(true, true, "1d", "2026-11-28T10:00:00Z",
                List.of(new WorkspaceView("indexer", 2, List.of("indexer"), 5 * 1048576L)),
                new Usage(1, 2, 3 * 1048576L, 40, 1),
                new Limits(10, 300 * 1048576L, 30, 5000, 5, 3, 20 * 1048576L));

        @Override
        public IssuedKeys issueKeys(long tgId, String ttl) throws ServiceException {
            calls.add("issue " + tgId + " " + ttl);
            if (failure != null) {
                throw failure;
            }
            return new IssuedKeys("mcp_r_READ", "mcp_w_WRITE", "mcp_s_STATS", ttl, "2026-11-28T10:00:00Z");
        }

        @Override
        public ResetResult reset(long tgId) {
            calls.add("reset " + tgId);
            return new ResetResult(true, 2);
        }

        @Override
        public Me me(long tgId) {
            calls.add("me " + tgId);
            return me;
        }

        @Override
        public Stats stats() {
            calls.add("stats");
            return new Stats(7, 3, 1, 2, 0);
        }
    }

    private FakeApi api;
    private FakeService service;
    private Instant now;
    private BotHandler handler;

    @BeforeEach
    void setUp() {
        api = new FakeApi();
        service = new FakeService();
        now = Instant.parse("2026-11-27T10:00:00Z");
        handler = new BotHandler(api, service,
                new BotProperties("t", "http://s", "k", "https://mcp.example.org/", "http://tg", 50), () -> now);
    }

    private static Update text(long user, String text) {
        return new Update(1, user, true, user, text, null, null);
    }

    private static Update press(long user, String data) {
        return new Update(2, user, true, user, null, "cb-" + data, data);
    }

    private Sent last() {
        return api.sent.get(api.sent.size() - 1);
    }

    @Test
    void startShowsTheMenu() {
        handler.handle(text(42, "/start"));

        assertThat(last().chat()).isEqualTo(42);
        assertThat(last().html()).contains("mcp-sql");
        assertThat(last().keyboard().stream().flatMap(List::stream).map(TelegramApi.Button::data))
                .contains("keys", "me", "stats", "help", "reset");
    }

    @Test
    void commandsWithTheBotNameWork() {
        handler.handle(text(42, "/stats@mcp_sql_bot"));

        assertThat(service.calls).containsExactly("stats");
        assertThat(last().html()).contains("Зарегистрировано: 7");
    }

    @Test
    void groupChatsAreIgnored() {
        handler.handle(new Update(1, -100, false, 42, "/start", null, null));

        assertThat(api.sent).isEmpty();
    }

    @Test
    void keysAreIssuedForTheChosenTtlAndShownOnce() {
        handler.handle(press(42, "keys"));
        assertThat(last().keyboard().get(0)).extracting(TelegramApi.Button::data)
                .containsExactly("issue:5h", "issue:1d", "issue:7d");

        handler.handle(press(42, "issue:1d"));

        assertThat(service.calls).containsExactly("issue 42 1d");
        assertThat(last().html()).contains("<code>mcp_w_WRITE</code>", "<code>mcp_r_READ</code>",
                "https://mcp.example.org/mcp/ИМЯ_БАЗЫ", "2026-11-28 10:00 UTC");
        assertThat(api.answered).contains("cb-keys", "cb-issue:1d");
    }

    @Test
    void anUnknownTtlIsNotSentToTheServer() {
        handler.handle(press(42, "issue:99y"));

        assertThat(service.calls).isEmpty();
        assertThat(last().html()).contains("Не понял");
    }

    @Test
    void refusalsAreExplained() {
        service.failure = new ServiceException(429, "KEY_ISSUE_LIMIT", "limit");
        handler.handle(press(42, "issue:1d"));
        assertThat(last().html()).contains("Лимит выдач").contains("Удалить всё");

        service.failure = new ServiceException(503, "CAPACITY", "full");
        handler.handle(press(42, "issue:1d"));
        assertThat(last().html()).contains("заполнен");

        service.failure = new ServiceException(0, "UNAVAILABLE", "connection refused");
        handler.handle(press(42, "issue:1d"));
        assertThat(last().html()).contains("временно недоступен").doesNotContain("refused");
    }

    @Test
    void textFromTheServerIsEscaped() {
        service.failure = new ServiceException(400, "", "<script>alert(1)</script>");

        handler.handle(press(42, "issue:1d"));

        assertThat(last().html()).contains("&lt;script&gt;").doesNotContain("<script>");
    }

    @Test
    void resetNeedsAConfirmation() {
        handler.handle(text(42, "/reset"));
        assertThat(service.calls).isEmpty();
        assertThat(last().keyboard().get(0)).extracting(TelegramApi.Button::data)
                .containsExactly("reset:yes", "reset:no");

        handler.handle(press(42, "reset:no"));
        assertThat(service.calls).isEmpty();

        handler.handle(press(42, "reset:yes"));
        assertThat(service.calls).containsExactly("reset 42");
        assertThat(last().html()).contains("файлов: 2");
    }

    @Test
    void myDataShowsBasesAndUsage() {
        handler.handle(press(42, "me"));

        assertThat(last().html()).contains("<code>indexer</code>", "5.0 МБ", "Запросы MCP: 40 из 5000",
                "Выдачи ключей: 1 из 5", "Объём записи: 3.0 из 300.0 МБ");
    }

    @Test
    void myDataWithoutKeysSaysSo() {
        service.me = new ServiceClient.Me(false, false, "", "", List.of(), null, null);

        handler.handle(press(42, "me"));

        assertThat(last().html()).contains("Активных ключей нет");
    }

    @Test
    void tooManyActionsAreDroppedUntilTheWindowPasses() {
        for (int i = 0; i < 12; i++) {
            handler.handle(press(42, "stats"));
        }
        assertThat(service.calls).hasSize(12);

        handler.handle(press(42, "stats"));
        assertThat(service.calls).hasSize(12);
        handler.handle(press(43, "stats"));
        assertThat(service.calls).hasSize(13);

        now = now.plus(Duration.ofMinutes(2));
        handler.handle(press(42, "stats"));
        assertThat(service.calls).hasSize(14);
    }

    @Test
    void sendFailuresDoNotBreakTheHandler() {
        TelegramApi failing = new TelegramApi() {
            @Override
            public Updates getUpdates(long offset, int timeoutSeconds) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void sendMessage(long chatId, String html, List<List<Button>> keyboard) throws IOException {
                throw new IOException("blocked by the user");
            }

            @Override
            public void answerCallback(String callbackId) {
            }
        };
        BotHandler h = new BotHandler(failing, service,
                new BotProperties("t", "http://s", "k", "", "http://tg", 50), () -> now);

        h.handle(text(42, "/start"));
    }
}
