package ru.mcs.mcpsql.bot;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Telegram Bot API over plain HTTPS. The token is part of the URL, so URLs never go into messages or logs. */
@Component
public class HttpTelegramApi implements TelegramApi {

    private final HttpClient http;
    private final JsonMapper json;
    private final String base;

    public HttpTelegramApi(BotProperties properties, JsonMapper json, HttpClient http) {
        this.http = http;
        this.json = json;
        this.base = trimSlash(properties.telegramUrl()) + "/bot" + properties.token() + "/";
    }

    @Override
    public Updates getUpdates(long offset, int timeoutSeconds) throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("offset", offset);
        body.put("timeout", timeoutSeconds);
        body.putArray("allowed_updates").add("message").add("callback_query");
        JsonNode result = call("getUpdates", body, Duration.ofSeconds(timeoutSeconds + 15L));
        List<Update> items = new ArrayList<>();
        long next = offset;
        for (JsonNode raw : result) {
            long id = raw.path("update_id").longValue();
            next = Math.max(next, id + 1);
            Update update = parse(id, raw);
            if (update != null) {
                items.add(update);
            }
        }
        return new Updates(items, next);
    }

    @Override
    public void sendMessage(long chatId, String html, List<List<Button>> keyboard) throws IOException {
        ObjectNode body = json.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        if (keyboard != null && !keyboard.isEmpty()) {
            ArrayNode rows = body.putObject("reply_markup").putArray("inline_keyboard");
            for (List<Button> row : keyboard) {
                ArrayNode cells = rows.addArray();
                for (Button button : row) {
                    cells.addObject().put("text", button.text()).put("callback_data", button.data());
                }
            }
        }
        callQuietly("sendMessage", body);
    }

    @Override
    public void answerCallback(String callbackId) throws IOException {
        ObjectNode body = json.createObjectNode();
        body.put("callback_query_id", callbackId);
        callQuietly("answerCallbackQuery", body);
    }

    private void callQuietly(String method, ObjectNode body) throws IOException {
        try {
            call(method, body, Duration.ofSeconds(15));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private Update parse(long id, JsonNode raw) {
        JsonNode callback = raw.path("callback_query");
        if (callback.isObject()) {
            JsonNode chat = callback.path("message").path("chat");
            if (!chat.isObject()) {
                return null;
            }
            return new Update(id, chat.path("id").longValue(), "private".equals(str(chat.path("type"))),
                    callback.path("from").path("id").longValue(), null,
                    str(callback.path("id")), str(callback.path("data")));
        }
        JsonNode message = raw.path("message");
        if (message.isObject() && !message.path("text").isMissingNode()) {
            JsonNode chat = message.path("chat");
            return new Update(id, chat.path("id").longValue(), "private".equals(str(chat.path("type"))),
                    message.path("from").path("id").longValue(), str(message.path("text")), null, null);
        }
        return null;
    }

    private JsonNode call(String method, ObjectNode body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + method))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root;
        try {
            root = json.readTree(response.body());
        } catch (RuntimeException e) {
            throw new IOException("telegram " + method + ": unreadable answer, HTTP " + response.statusCode());
        }
        if (!root.path("ok").asBoolean()) {
            throw new IOException("telegram " + method + " failed: HTTP " + response.statusCode() + " "
                    + str(root.path("description")));
        }
        return root.path("result");
    }

    static String str(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? "" : node.asString();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
