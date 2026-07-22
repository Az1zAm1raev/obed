package com.example.lunchbot.all;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TelegramClient {

    public static final Map<String, Object> EMPTY_KEYBOARD = Map.of("inline_keyboard", List.of());

    private final RestClient api;
    private final RestClient files;

    /** username бота. Спрашиваем у Telegram при старте — руками задавать не нужно. */
    private volatile String username;

    public TelegramClient(@Value("${telegram.bot-token}") String token) {
        if (token == null || token.isBlank() || !token.contains(":")) {
            throw new IllegalStateException("Invalid TELEGRAM_BOT_TOKEN");
        }
        // Голый JDK HttpClient — не зависит от версии Spring Boot.
        // connectTimeout 10с; read-таймаут для long polling задаётся per-request ниже.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        this.api = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .baseUrl("https://api.telegram.org/bot" + token)
                .build();
        this.files = RestClient.builder()
                .baseUrl("https://api.telegram.org/file/bot" + token)
                .build();
    }

    /** @return username бота без @, например «MyLunchBot». Нужен для deep link. */
    public String username() {
        String local = username;
        if (local == null) {
            synchronized (this) {
                local = username;
                if (local == null) {
                    JsonNode me = api.post().uri("/getMe").retrieve().body(JsonNode.class);
                    if (me == null || !me.path("ok").asBoolean()) {
                        throw new IllegalStateException("getMe failed: проверьте TELEGRAM_BOT_TOKEN");
                    }
                    local = me.get("result").get("username").asText();
                    username = local;
                }
            }
        }
        return local;
    }

    // ------------------------------------------------------------ updates

    public JsonNode getUpdates(long offset) {
        // timeout 25 — long polling: Telegram держит соединение до 25с и сам его закроет.
        // JDK HttpClient без request-таймаута спокойно этого дожидается.
        return api.post().uri("/getUpdates")
                .body(Map.of(
                        "offset", offset,
                        "timeout", 25,
                        "allowed_updates", List.of("message", "callback_query")))
                .retrieve().body(JsonNode.class);
    }

    // ----------------------------------------------------------- messages

    public JsonNode sendMessage(long chatId, String text) {
        return sendMessage(chatId, text, EMPTY_KEYBOARD);
    }

    public JsonNode sendMessage(long chatId, String text, Object replyMarkup) {
        return api.post().uri("/sendMessage")
                .body(Map.of("chat_id", chatId, "text", text, "reply_markup", replyMarkup))
                .retrieve().body(JsonNode.class);
    }

    public void editMessageText(long chatId, long messageId, String text, Object replyMarkup) {
        api.post().uri("/editMessageText")
                .body(Map.of("chat_id", chatId, "message_id", messageId,
                        "text", text, "reply_markup", replyMarkup))
                .retrieve().toBodilessEntity();
    }

    public void answerCallback(String callbackId, String text) {
        answerCallback(callbackId, text, false);
    }

    public void answerCallback(String callbackId, String text, boolean alert) {
        api.post().uri("/answerCallbackQuery")
                .body(Map.of("callback_query_id", callbackId, "text", text, "show_alert", alert))
                .retrieve().toBodilessEntity();
    }

    /**
     * Список команд в подсказке при вводе «/».
     * scope = null — всем; иначе, например, Map.of("type","all_chat_administrators").
     */
    public void setMyCommands(List<Map<String, String>> commands, Object scope) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("commands", commands);
        if (scope != null) {
            body.put("scope", scope);
        }
        try {
            api.post().uri("/setMyCommands").body(body).retrieve().body(JsonNode.class);
        } catch (Exception ignored) {
            // подсказка команд — не критично, бот работает и без неё
        }
    }

    public JsonNode getChatMember(long chatId, long userId) {
        return api.post().uri("/getChatMember")
                .body(Map.of("chat_id", chatId, "user_id", userId))
                .retrieve().body(JsonNode.class);
    }

    // -------------------------------------------------------------- photo

    public void sendPhoto(long chatId, String fileId, String caption) {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("photo", fileId);
        if (caption != null && !caption.isBlank()) {
            body.put("caption", caption);
        }
        api.post().uri("/sendPhoto").body(body).retrieve().toBodilessEntity();
    }

    /** Альбом. Telegram принимает от 2 до 10 элементов за раз. */
    public void sendMediaGroup(long chatId, List<Map<String, Object>> media) {
        if (media.isEmpty()) return;
        if (media.size() == 1) {
            Map<String, Object> one = media.get(0);
            sendPhoto(chatId, (String) one.get("media"), (String) one.get("caption"));
            return;
        }
        for (int i = 0; i < media.size(); i += 10) {
            List<Map<String, Object>> chunk = media.subList(i, Math.min(i + 10, media.size()));
            api.post().uri("/sendMediaGroup")
                    .body(Map.of("chat_id", chatId, "media", chunk))
                    .retrieve().toBodilessEntity();
        }
    }

    // --------------------------------------------------------- скачивание

    /** file_id -> байты. Лимит Telegram на скачивание ботом — 20 МБ. */
    public byte[] download(String fileId) {
        JsonNode meta = api.post().uri("/getFile")
                .body(Map.of("file_id", fileId))
                .retrieve().body(JsonNode.class);

        if (meta == null || !meta.path("ok").asBoolean()) {
            throw new IllegalStateException("getFile failed");
        }
        String path = meta.get("result").get("file_path").asText();

        return files.get().uri("/" + path).retrieve().body(byte[].class);
    }
}