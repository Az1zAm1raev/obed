package com.example.lunchbot.receipt;

import com.example.lunchbot.all.TelegramClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final TelegramClient telegram;
    private final ReceiptTextExtractor extractor;
    private final ReceiptVerifier verifier;
    private final PaymentRepository repository;
    private final SettingsRepository settings;

    @Value("${lunch.admin-chat-id}")
    private long adminChatId;

    private final PriceBook priceBook;

    // -------------------------------------------------- баланс в деньгах

    /** Право на одно бесплатное блюдо: у получателя (recipient.id). */
    public boolean hasFreeDish(long userId) {
        Long recipientId = settings.recipientId();
        return recipientId != null && recipientId.longValue() == userId;
    }

    /** Оплачено всего (сумма чеков). */
    public int paidTotal(long pollId, long userId) {
        return repository.paidTotal(pollId, userId);
    }

    /**
     * Свободный баланс с учётом уже выбранных ПЛАТНЫХ блюд.
     * @param paidDishes сколько платных блюд человек уже взял
     */
    public int balance(long pollId, long userId, int paidDishes) {
        return paidTotal(pollId, userId) - paidDishes * priceBook.priceFor(userId);
    }

    /** Хватает ли на ещё одно блюдо. */
    public boolean canAfford(long pollId, long userId, int paidDishes) {
        return balance(pollId, userId, paidDishes) >= priceBook.priceFor(userId);
    }

    public int priceFor(long userId) {
        return priceBook.priceFor(userId);
    }

    public java.util.Map<Long, Integer> paidByUser(long pollId) {
        return repository.paidByUser(pollId);
    }

    public void clearPendingByPoll(long pollId) {
        repository.clearPendingByPoll(pollId);
    }

    // -------------------------------------------------- deep link

    /** /start pay_<pollId> */
    public void startReceiptFlow(long userId, long chatId, long pollId) {
        if (!pollGate.test(pollId)) {
            telegram.sendMessage(chatId, "Этот опрос уже закрыт. Дождитесь нового.");
            return;
        }
        repository.setPending(userId, pollId);

        String qr = settings.qrFileId();
        String hint = qr != null ? "\n\nМожно оплатить по QR выше." : "";

        String caption = "🧾 Пришлите чек об оплате — файлом PDF.\n\n"
                + "Получатель: " + settings.recipientName() + "\n"
                + "Номер: " + settings.recipientPhone() + hint + "\n\n"
                + "В приложении банка: «Скачать чек» / «Поделиться» → PDF.\n"
                + "Скриншот не подойдёт — нужен PDF-файл.\n\n"
                + "За каждое блюдо — отдельный чек.";

        if (qr != null) {
            telegram.sendPhoto(chatId, qr, "Отсканируйте для оплаты");
        }
        telegram.sendMessage(chatId, caption);
    }

    // -------------------------------------------------- приём чека

    /** Пришёл документ в личку. Только PDF. */
    public void handleReceipt(long userId, long chatId, String fileId, String fileName, String displayName) {
        Long pollId = repository.findPendingPoll(userId);
        if (pollId == null || !pollGate.test(pollId)) {
            telegram.sendMessage(chatId,
                    "Нет активного заказа для этого чека.\n"
                            + "Нажмите «🧾 Подтвердить оплату» под сегодняшним опросом, потом пришлите чек.");
            return;
        }

        ReceiptVerifier.Result result;
        try {
            byte[] bytes = telegram.download(fileId);
            String text = extractor.extract(bytes, fileName);
            result = verifier.verify(text);
        } catch (ReceiptTextExtractor.NotPdfException e) {
            telegram.sendMessage(chatId,
                    "❌ Нужен PDF-файл, а не картинка.\n\n"
                            + "В приложении банка: «Скачать чек» / «Поделиться» → выберите PDF, "
                            + "а не скриншот.");
            return;
        } catch (Exception e) {
            log.warn("Не смог прочитать чек от {}: {}", userId, e.toString());
            telegram.sendMessage(chatId, "❌ Не удалось прочитать файл. Пришлите PDF-чек из приложения банка.");
            return;
        }

        // REJECTED не сохраняем — просто объясняем и просим другой чек.
        if (result.status() == ReceiptVerifier.Status.REJECTED) {
            telegram.sendMessage(chatId, "❌ " + result.reason() + "\n\nПришлите корректный чек.");
            return;
        }

        if (result.amount() <= 0) {
            telegram.sendMessage(chatId,
                    "❌ Не удалось распознать сумму в чеке. Пришлите PDF из приложения банка.");
            return;
        }

        Long receiptId = repository.saveReceipt(pollId, userId,
                result.status().name(), fileId, result.txId(), result.amount());

        if (receiptId == null) {
            telegram.sendMessage(chatId,
                    "⚠️ Этот чек уже засчитан. Пришлите другой чек.");
            return;
        }

        if (result.status() == ReceiptVerifier.Status.APPROVED) {
            // pending НЕ очищаем: человек может прислать ещё чек за второе блюдо.
            // Очистится при закрытии/создании опроса.
            int price = priceBook.priceFor(userId);
            int total = paidTotal(pollId, userId);
            int dishes = total / price;
            sendBackToPoll(chatId, pollId,
                    "✅ Оплата подтверждена: " + result.amount() + " сом.\n"
                            + "На балансе " + total + " сом — это " + dishes
                            + " блюд(а) по " + price + ".\n"
                            + "Возвращайтесь к опросу и выбирайте.\n"
                            + "Ещё блюдо — просто пришлите сюда ещё чек, кнопку жать не надо.");
        } else {
            telegram.sendMessage(chatId, "⏳ Чек отправлен на проверку. Я напишу, как только его подтвердят.");
            askAdmin(receiptId, userId, fileId, displayName, result);
        }
    }

    private void askAdmin(long receiptId, long userId, String fileId, String displayName,
                          ReceiptVerifier.Result result) {
        telegram.sendMessage(adminChatId,
                "🧾 Чек от " + displayName + "\n\n" + result.reason() + "\n\n" + result.breakdown());

        telegram.sendMessage(adminChatId, "Засчитать?", Map.of("inline_keyboard", List.of(
                List.of(
                        Map.of("text", "✅ Да", "callback_data", "pay_ok:" + receiptId + ":" + userId),
                        Map.of("text", "❌ Нет", "callback_data", "pay_no:" + receiptId + ":" + userId)
                ))));
    }

    /** Кнопки в админском чате. receiptId — id конкретного чека. */
    public String resolveManually(long receiptId, long userId, boolean approve) {
        if (approve) {
            repository.approveReceipt(receiptId);
            telegram.sendMessage(userId, "✅ Оплата подтверждена. Возвращайтесь к опросу.");
            return "Засчитано";
        }
        repository.deleteReceipt(receiptId);
        telegram.sendMessage(userId, "❌ Чек не принят. Пришлите корректный PDF-чек.");
        return "Отклонено";
    }

    // -------------------------------------------------- смена реквизита

    /** Кнопка-ссылка обратно на сообщение опроса в группе. */
    private void sendBackToPoll(long chatId, long pollId, String text) {
        String link = pollLink != null ? pollLink.apply(pollId) : null;
        if (link != null) {
            telegram.sendMessage(chatId, text, Map.of("inline_keyboard", List.of(List.of(
                    Map.of("text", "◀️ К опросу", "url", link)))));
        } else {
            telegram.sendMessage(chatId, text);
        }
    }

    /** Ставится из LunchPollService: pollId -> ссылка на сообщение опроса. */
    private java.util.function.Function<Long, String> pollLink;
    public void setPollLinkResolver(java.util.function.Function<Long, String> f) {
        this.pollLink = f;
    }

    /** Ставится из LunchPollService: активен ли опрос. */
    private java.util.function.LongPredicate pollGate = id -> true;
    public void setPollGate(java.util.function.LongPredicate g) {
        this.pollGate = g;
    }

    /** id основной группы, либо переданный fallback. */
    public long mainChatIdOr(long fallback) {
        Long m = settings.mainChatId();
        return m != null ? m : fallback;
    }

    /** /setmain — запомнить эту группу как основную. */
    public void setMainGroup(long chatId, long adminId, boolean isGroup) {
        if (!isGroup) {
            telegram.sendMessage(chatId, "Команду /setmain нужно вызвать В ОСНОВНОЙ ГРУППЕ, не в личке.");
            return;
        }
        settings.setMainChatId(chatId, adminId);
        telegram.sendMessage(chatId,
                "✅ Эта группа теперь основная.\n"
                        + "id: " + chatId);
    }

    /** /setqr + фото — сохранить QR оплаты. */
    public void setQr(long chatId, long adminId, String fileId) {
        settings.setQr(fileId, adminId);
        telegram.sendMessage(chatId, "✅ QR для оплаты сохранён. Теперь он показывается при запросе чека.");
    }

    /** /qr — показать текущий QR. */
    public void showQr(long chatId) {
        String qr = settings.qrFileId();
        if (qr == null) {
            telegram.sendMessage(chatId, "QR ещё не задан. Админ: пришлите /setqr с картинкой QR.");
            return;
        }
        telegram.sendPhoto(chatId, qr, "QR для оплаты обеда\n" + settings.recipientName());
    }

    /**
     * /recipient 996708760011 Азиз Амираев 1209071685
     * Последний аргумент — id получателя. Он же получает одно бесплатное блюдо.
     * id опционален: если не указан, прежний сохраняется.
     */
    public void changeRecipient(long chatId, long adminId, String text) {
        // телефон | имя (может быть из нескольких слов) | id (последнее слово, если это число)
        String[] parts = text.strip().split("\\s+");
        if (parts.length < 3) {
            Long rid = settings.recipientId();
            telegram.sendMessage(chatId,
                    "Формат: /recipient <телефон> <Имя Фамилия> <id>\n"
                            + "id — Telegram id получателя, он получает одно бесплатное блюдо.\n\n"
                            + "Сейчас: " + settings.recipientName() + " · " + settings.recipientPhone()
                            + (rid != null ? " · id " + rid : ""));
            return;
        }

        String phone = parts[1].replaceAll("[^0-9+]", "");
        if (phone.replaceAll("\\D", "").length() < 9) {
            telegram.sendMessage(chatId, "Не похоже на номер телефона: " + parts[1]);
            return;
        }

        // Последний токен — id, если это чистое число длиной >= 6.
        Long recipientId = null;
        int nameEnd = parts.length;
        String last = parts[parts.length - 1];
        if (last.matches("\\d{6,}")) {
            recipientId = Long.parseLong(last);
            nameEnd = parts.length - 1;
        }

        StringBuilder name = new StringBuilder();
        for (int i = 2; i < nameEnd; i++) {
            if (name.length() > 0) name.append(' ');
            name.append(parts[i]);
        }
        if (name.length() == 0) {
            telegram.sendMessage(chatId, "Не указано имя получателя.");
            return;
        }

        String previous = settings.recipientPhone();
        settings.changeRecipient(phone.replaceAll("\\D", ""), name.toString(), recipientId, adminId);

        String idNote = recipientId != null
                ? "\nБесплатное блюдо теперь у id " + recipientId + "."
                : "\nid не изменён" + (settings.recipientId() != null ? " (" + settings.recipientId() + ")" : "") + ".";

        telegram.sendMessage(chatId,
                "✅ Реквизит обновлён: " + name + " · " + phone + idNote + "\n\n"
                        + "Чеки на старый номер " + previous + " теперь отклоняются.");
    }
}