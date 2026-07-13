package com.example.lunchbot.all;

import com.example.lunchbot.dish.CatalogHandler;
import com.example.lunchbot.receipt.PaymentService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramUpdateWorker {

    private final TelegramClient telegram;
    private final LunchPollService lunchPollService;
    private final PaymentService payments;
    private final CatalogHandler catalog;

    @Value("${lunch.admin-chat-id}")
    private long adminChatId;

    private long offset = 0;
    private volatile boolean running = true;

    /**
     * Long polling в отдельном потоке. getUpdates держит соединение до 25 секунд
     * (timeout в TelegramClient) и спит на стороне Telegram, пока не придёт сообщение.
     * Это на порядок легче для ноутбука, чем опрос каждую секунду.
     */
    @PostConstruct
    public void start() {
        Thread t = new Thread(this::pollLoop, "telegram-poll");
        t.setDaemon(true);
        t.start();
        log.info("Telegram long polling запущен");
    }

    private void pollLoop() {
        while (running) {
            try {
                JsonNode response = telegram.getUpdates(offset);
                if (response == null || !response.path("ok").asBoolean()) {
                    continue;
                }
                for (JsonNode update : response.get("result")) {
                    offset = update.get("update_id").asLong() + 1;
                    try {
                        if (update.has("message")) {
                            handleMessage(update.get("message"));
                        } else if (update.has("callback_query")) {
                            handleCallback(update.get("callback_query"));
                        }
                    } catch (Exception e) {
                        log.error("update {} failed", update.get("update_id"), e);
                    }
                }
            } catch (Exception e) {
                // сеть отвалилась или Telegram недоступен — подождём и попробуем снова
                log.warn("polling error: {}", e.toString());
                sleep(3000);
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================================================== маршрутизация

    /**
     * ПОРЯДОК ВАЖЕН:
     *   1. фото от админа в ГРУППЕ, либо фото с подписью «/dish ...» -> каталог
     *   2. фото/PDF в личке                                          -> чек об оплате
     *   3. текст + активный диалог каталога                          -> каталог
     *   4. текст-команда                                             -> команды
     *
     * Чеки принимаются ТОЛЬКО в личке: в группе их видели бы все.
     * Каталог живёт в группе, поэтому обычное фото в личке никогда не уйдёт в каталог —
     * иначе админ не смог бы прислать боту собственный чек.
     */
    private void handleMessage(JsonNode message) {
        long chatId = message.get("chat").get("id").asLong();
        String chatType = message.get("chat").get("type").asText();
        boolean isPrivate = "private".equals(chatType);

        JsonNode from = message.get("from");
        long userId = from.get("id").asLong();

        // ---------- 1 и 2: вложения ----------
        FileRef file = extractFile(message);
        if (file != null) {
            String caption = message.has("caption") ? message.get("caption").asText() : null;
            boolean dishCaption = caption != null && caption.strip().toLowerCase().startsWith("/dish ");
            boolean qrCaption = caption != null && caption.strip().toLowerCase().startsWith("/setqr");

            if (qrCaption && isAdmin(message)) {
                payments.setQr(chatId, userId, file.fileId());
                return;
            }

            // В личке в каталог попадает только фото с явной подписью /dish.
            // Всё остальное в личке — чек.
            if ((!isPrivate || dishCaption) && isAdmin(message)
                    && catalog.handlePhoto(userId, chatId, caption, file.fileId(), file.uniqueId())) {
                return;
            }
            if (isPrivate) {
                payments.handleReceipt(userId, chatId, file.fileId(), file.fileName(), displayName(from));
            }
            return;
        }

        if (!message.has("text")) {
            return;
        }
        String text = message.get("text").asText().strip();

        // ---------- 3: диалог каталога (работает и в группе) ----------
        if (isAdmin(message) && catalog.handleText(userId, chatId, text)) {
            return;
        }

        // ---------- 4: команды ----------
        if (text.startsWith("/start")) {
            handleStart(userId, chatId, text);
            return;
        }

        if (text.startsWith("/lunch") || text.equals("/close") || text.equals("/summary")
                || text.equals("/catalog") || text.startsWith("/recipient")
                || text.equals("/money") || text.startsWith("/setqr")
                || text.equals("/setmain")) {
            if (!isAdmin(message)) {
                telegram.sendMessage(chatId, "⛔ У вас нет прав для этой команды.");
                return;
            }
        }

        if (text.startsWith("/lunch")) {
            LunchPollRequest request = LunchPollRequestParser.parse(text);
            lunchPollService.createPoll(chatId, request.title(), request.options());
            return;
        }
        if (text.equals("/catalog")) {
            catalog.showCatalog(chatId);
            return;
        }
        if (text.startsWith("/recipient")) {
            String rest = text.substring("/recipient".length()).strip();
            if (rest.isEmpty()) {
                catalog.startRecipientDialog(userId, chatId);   // пошаговый ввод
            } else {
                payments.changeRecipient(chatId, userId, text); // одной строкой, если указали
            }
            return;
        }
        if (text.equals("/money")) {
            long src = isPrivate ? payments.mainChatIdOr(chatId) : chatId;
            Long pollId = lunchPollService.findLastPollId(src);
            if (pollId == null) telegram.sendMessage(chatId, "Нет голосования. В основной группе задайте /setmain.");
            else lunchPollService.sendMoney(chatId, pollId);
            return;
        }
        if (text.equals("/qr")) {
            payments.showQr(chatId);
            return;
        }
        if (text.equals("/setmain")) {
            boolean isGroup = !"private".equals(chatType);
            payments.setMainGroup(chatId, userId, isGroup);
            return;
        }
        if (text.equals("/menu")) {
            catalog.showMenuPhotos(chatId, lunchPollService.findActivePollId(chatId));
            return;
        }
        if (text.equals("/close")) {
            Long pollId = lunchPollService.findActivePollId(chatId);
            if (pollId == null) telegram.sendMessage(chatId, "Нет активного голосования.");
            else lunchPollService.closePoll(chatId, pollId);
            return;
        }
        if (text.equals("/summary")) {
            Long pollId = lunchPollService.findActivePollId(chatId);
            if (pollId == null) telegram.sendMessage(chatId, "Нет активного голосования.");
            else lunchPollService.sendSummary(chatId, pollId);
        }
    }

    /** Deep link из кнопки «🧾 Подтвердить оплату»: /start pay_123 */
    private void handleStart(long userId, long chatId, String text) {
        String[] parts = text.split("\\s+", 2);
        if (parts.length == 2 && parts[1].startsWith("pay_")) {
            try {
                long pollId = Long.parseLong(parts[1].substring(4));
                payments.startReceiptFlow(userId, chatId, pollId);
                return;
            } catch (NumberFormatException ignored) {
                // упадём в приветствие
            }
        }
        telegram.sendMessage(chatId, "Привет! Я бот для обедов. Нажмите «🧾 Подтвердить оплату» под опросом.");
    }

    // ==================================================== callback'и

    private void handleCallback(JsonNode callback) {
        String callbackId = callback.get("id").asText();
        String data = callback.get("data").asText();

        JsonNode user = callback.get("from");
        long userId = user.get("id").asLong();
        long chatId = callback.get("message").get("chat").get("id").asLong();
        String username = user.has("username") ? user.get("username").asText() : null;

        // --- голосование ---
        if (data.startsWith("lunch_vote:")) {
            String[] p = data.split(":");
            long pollId = Long.parseLong(p[1]);
            VoteResult r = lunchPollService.vote(
                    pollId, Long.parseLong(p[2]), userId, username, displayName(user));
            if (r == VoteResult.PAYMENT_REQUIRED) {
                telegram.answerCallback(callbackId, lunchPollService.shortfallMessage(pollId, userId), true);
            } else {
                telegram.answerCallback(callbackId, messageFor(r));
            }
            return;
        }
        if (data.startsWith("lunch_cancel:")) {
            VoteResult r = lunchPollService.cancelVote(Long.parseLong(data.split(":")[1]), userId);
            telegram.answerCallback(callbackId, messageFor(r));
            return;
        }
        if (data.startsWith("lunch_add:")) {
            VoteResult r = lunchPollService.enableAddMode(Long.parseLong(data.split(":")[1]), userId);
            telegram.answerCallback(callbackId, messageFor(r));
            return;
        }

        // --- ручная проверка чека, только в админском чате ---
        if (data.startsWith("pay_ok:") || data.startsWith("pay_no:")) {
            if (chatId != adminChatId) {
                telegram.answerCallback(callbackId, "Недоступно");
                return;
            }
            String[] p = data.split(":");
            // pay_ok:<receiptId>:<userId>
            String result = payments.resolveManually(
                    Long.parseLong(p[1]), Long.parseLong(p[2]), p[0].equals("pay_ok"));
            telegram.answerCallback(callbackId, result);
            return;
        }

        // --- каталог: кнопки видны всем в группе, жать может только админ ---
        if (data.startsWith("dish") || data.startsWith("q_")) {
            if (!isChatAdmin(chatId, userId)) {
                telegram.answerCallback(callbackId, "⛔ Только для админов", true);
                return;
            }
            String answer = catalog.handleCallback(userId, chatId, data);
            telegram.answerCallback(callbackId, answer != null ? answer : "");
        }
    }

    // ==================================================== утилиты

    private record FileRef(String fileId, String uniqueId, String fileName) {}

    /** Фото (берём максимальный размер) или PDF-документ. */
    private FileRef extractFile(JsonNode message) {
        if (message.has("photo")) {
            JsonNode photos = message.get("photo");
            JsonNode biggest = photos.get(photos.size() - 1);
            return new FileRef(biggest.get("file_id").asText(),
                    biggest.get("file_unique_id").asText(), "photo.jpg");
        }
        if (message.has("document")) {
            JsonNode doc = message.get("document");
            String name = doc.has("file_name") ? doc.get("file_name").asText() : "receipt";
            return new FileRef(doc.get("file_id").asText(), doc.get("file_unique_id").asText(), name);
        }
        return null;
    }

    private String displayName(JsonNode user) {
        String first = user.has("first_name") ? user.get("first_name").asText() : "";
        String last = user.has("last_name") ? user.get("last_name").asText() : "";
        String name = (first + " " + last).strip();
        return name.isEmpty() ? "Без имени" : name;
    }

    private boolean isAdmin(JsonNode message) {
        long userId = message.get("from").get("id").asLong();
        long chatId = message.get("chat").get("id").asLong();

        if ("private".equals(message.get("chat").get("type").asText())) {
            return userId == adminChatId;   // в личке админ — только владелец admin-chat-id
        }
        return isChatAdmin(chatId, userId);
    }

    /** Создатель или администратор группы. */
    private boolean isChatAdmin(long chatId, long userId) {
        if (chatId == userId) {
            return userId == adminChatId;   // личка
        }
        try {
            JsonNode response = telegram.getChatMember(chatId, userId);
            String status = response.get("result").get("status").asText();
            return Set.of("creator", "administrator").contains(status);
        } catch (Exception e) {
            log.warn("getChatMember failed: {}", e.toString());
            return false;
        }
    }

    private String messageFor(VoteResult result) {
        return switch (result) {
            case SAVED -> "✅ Голос сохранён";
            case CANCELLED -> "❌ Все ваши голоса отменены";
            case POLL_CLOSED -> "🔒 Голосование уже закончилось";
            case ALREADY_VOTED -> "⚠️ У вас уже есть выбор. Нажмите «Добавить ещё блюдо»";
            case ADD_MODE_ENABLED -> "➕ Теперь выберите ещё одно блюдо";
            case PAYMENT_REQUIRED ->
                    "🧾 За это блюдо нужен чек.\n\nНажмите «Подтвердить оплату» под опросом "
                            + "и пришлите PDF-чек боту в личку. За каждое блюдо — отдельный чек.";
        };
    }
}