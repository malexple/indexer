package ru.mcs.mcpsql.bot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpTelegramApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private HttpTelegramApi api;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile String answer = "{\"ok\":true,\"result\":[]}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        BotProperties properties = new BotProperties("TOKEN", "http://unused", "k", "",
                "http://127.0.0.1:" + server.getAddress().getPort(), 50);
        api = new HttpTelegramApi(properties, JSON, HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void updatesAreParsedAndUnsupportedOnesStillAdvanceTheOffset() throws Exception {
        answer = "{\"ok\":true,\"result\":["
                + "{\"update_id\":10,\"message\":{\"message_id\":1,\"from\":{\"id\":555},"
                + "\"chat\":{\"id\":555,\"type\":\"private\"},\"text\":\"/start\"}},"
                + "{\"update_id\":11,\"callback_query\":{\"id\":\"cb1\",\"from\":{\"id\":555},\"data\":\"issue:1d\","
                + "\"message\":{\"message_id\":2,\"chat\":{\"id\":555,\"type\":\"private\"}}}},"
                + "{\"update_id\":12,\"edited_message\":{\"message_id\":3}},"
                + "{\"update_id\":13,\"message\":{\"message_id\":4,\"from\":{\"id\":9},"
                + "\"chat\":{\"id\":-5,\"type\":\"group\"},\"text\":\"hi\"}}]}";

        Updates updates = api.getUpdates(10, 1);

        assertThat(path.get()).isEqualTo("/botTOKEN/getUpdates");
        assertThat(JSON.readTree(body.get()).path("offset").longValue()).isEqualTo(10);
        assertThat(updates.nextOffset()).isEqualTo(14);
        assertThat(updates.items()).hasSize(3);
        Update message = updates.items().get(0);
        assertThat(message.updateId()).isEqualTo(10);
        assertThat(message.chatId()).isEqualTo(555);
        assertThat(message.userId()).isEqualTo(555);
        assertThat(message.privateChat()).isTrue();
        assertThat(message.text()).isEqualTo("/start");
        Update callback = updates.items().get(1);
        assertThat(callback.callbackId()).isEqualTo("cb1");
        assertThat(callback.callbackData()).isEqualTo("issue:1d");
        assertThat(callback.text()).isNull();
        assertThat(updates.items().get(2).privateChat()).isFalse();
    }

    @Test
    void messagesCarryHtmlAndInlineButtons() throws Exception {
        answer = "{\"ok\":true,\"result\":{}}";

        api.sendMessage(555, "<b>hi</b>", List.of(List.of(new TelegramApi.Button("Go", "keys"))));

        assertThat(path.get()).isEqualTo("/botTOKEN/sendMessage");
        JsonNode sent = JSON.readTree(body.get());
        assertThat(sent.path("chat_id").longValue()).isEqualTo(555);
        assertThat(sent.path("parse_mode").asString()).isEqualTo("HTML");
        JsonNode button = sent.path("reply_markup").path("inline_keyboard").get(0).get(0);
        assertThat(button.path("text").asString()).isEqualTo("Go");
        assertThat(button.path("callback_data").asString()).isEqualTo("keys");
    }

    @Test
    void aTelegramErrorBecomesAnIOExceptionWithoutTheToken() {
        answer = "{\"ok\":false,\"description\":\"Forbidden: bot was blocked by the user\"}";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api.answerCallback("x"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("blocked by the user")
                .hasMessageNotContaining("TOKEN");
    }
}
