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

    // -------------------------------------------------- баланс (для vote)

    public boolean hasUnspentReceipt(long pollId, long userId) {
        return repository.hasUnspentReceipt(pollId, userId);
    }

    public boolean spendOneReceipt(long pollId, long userId) {
        return repository.spendOneReceipt(pollId, userId);
    }

    public void refundAll(long pollId, long userId) {
        repository.refundAll(pollId, userId);
    }

    // -------------------------------------------------- deep link

    /** /start pay_<pollId> */
    public void startReceiptFlow(long userId, long chatId, long pollId) {
        repository.setPending(userId, pollId);
        telegram.sendMessage(chatId,
                "🧾 Пришлите чек об оплате — файлом PDF.\n\n"
                        + "Получатель: " + settings.recipientName() + "\n"
                        + "Номер: " + settings.recipientPhone() + "\n\n"
                        + "В приложении банка нажмите «Скачать чек» или «Поделиться» → PDF.\n"
                        + "Скриншот не подойдёт — нужен именно PDF-файл.\n\n"
                        + "За каждое блюдо — отдельный чек.");
    }

    // -------------------------------------------------- приём чека

    /** Пришёл документ в личку. Только PDF. */
    public void handleReceipt(long userId, long chatId, String fileId, String fileName, String displayName) {
        Long pollId = repository.findPendingPoll(userId);
        if (pollId == null) {
            telegram.sendMessage(chatId,
                    "Не понимаю, к какому обеду этот чек. Нажмите «🧾 Подтвердить оплату» под опросом.");
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

        Long receiptId = repository.saveReceipt(pollId, userId,
                result.status().name(), fileId, result.txId());

        if (receiptId == null) {
            telegram.sendMessage(chatId,
                    "⚠️ Этот чек уже засчитан. За каждое блюдо нужен отдельный, новый чек.");
            return;
        }

        if (result.status() == ReceiptVerifier.Status.APPROVED) {
            repository.clearPending(userId);
            telegram.sendMessage(chatId,
                    "✅ Оплата подтверждена. Возвращайтесь к опросу и выбирайте блюдо.\n"
                            + "Хотите ещё блюдо — пришлите ещё один чек.");
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
            repository.clearPending(userId);
            telegram.sendMessage(userId, "✅ Оплата подтверждена. Возвращайтесь к опросу.");
            return "Засчитано";
        }
        repository.deleteReceipt(receiptId);
        telegram.sendMessage(userId, "❌ Чек не принят. Пришлите корректный PDF-чек.");
        return "Отклонено";
    }

    // -------------------------------------------------- смена реквизита

    /** /recipient 996708760011 Азиз Амираев */
    public void changeRecipient(long chatId, long adminId, String text) {
        String[] p = text.strip().split("\\s+", 3);
        if (p.length < 3) {
            telegram.sendMessage(chatId,
                    "Формат: /recipient <телефон> <Имя Фамилия>\n"
                            + "Сейчас: " + settings.recipientName() + " · " + settings.recipientPhone());
            return;
        }
        String phone = p[1].replaceAll("[^0-9+]", "");
        if (phone.replaceAll("\\D", "").length() < 9) {
            telegram.sendMessage(chatId, "Не похоже на номер телефона: " + p[1]);
            return;
        }
        String previous = settings.recipientPhone();
        settings.changeRecipient(phone.replaceAll("\\D", ""), p[2].strip(), adminId);

        telegram.sendMessage(chatId,
                "✅ Реквизит обновлён: " + p[2].strip() + " · " + phone + "\n\n"
                        + "Чеки на старый номер " + previous + " теперь отклоняются "
                        + "с пояснением, что деньги ушли не туда.");
    }
}
