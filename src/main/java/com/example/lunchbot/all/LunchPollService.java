package com.example.lunchbot.all;

import com.example.lunchbot.dish.CatalogHandler;
import com.example.lunchbot.receipt.PaymentService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.example.lunchbot.all.TelegramClient.EMPTY_KEYBOARD;

@Slf4j
@Service
@RequiredArgsConstructor
public class LunchPollService {

    private final LunchPollRepository repository;
    private final TelegramClient telegram;
    private final PaymentService payments;
    private final CatalogHandler catalog;

    public Long findActivePollId(long chatId) {
        return repository.findActivePollId(chatId);
    }

    // ============================================================ создание

    /**
     * ИНВАРИАНТ: опрос создаётся всегда, из текста сообщения.
     * Каталог, фото и матчинг — необязательный побочный эффект.
     */
    @Transactional
    public void createPoll(long chatId, String title, List<String> options) {
        if (options.size() < 2) {
            telegram.sendMessage(chatId, "Не вижу блюд в сообщении. Каждое блюдо — с новой строки.");
            return;
        }

        repository.closeAllActivePolls(chatId);
        long pollId = repository.createPoll(chatId, title, options);

        JsonNode response = telegram.sendMessage(chatId, renderPoll(pollId), buildKeyboard(pollId));
        repository.updateMessageId(pollId, response.get("result").get("message_id").asInt());

        // ↓ всё, что ниже, не обязано получиться
        try {
            List<Long> optionIds = repository.findOptions(pollId).stream()
                    .map(o -> ((Number) o.get("id")).longValue())
                    .toList();
            catalog.enrich(pollId, optionIds, options, chatId);   // вопросы — в тот же чат, где опрос
        } catch (Exception e) {
            log.warn("Каталог недоступен, опрос работает без фото: {}", e.toString());
        }
    }

    // ============================================================ голосование

    @Transactional
    public VoteResult vote(long pollId, long optionId, long userId, String username, String fullName) {
        if (!repository.isPollActive(pollId)) {
            return VoteResult.POLL_CLOSED;
        }

        List<Long> currentVotes = repository.findUserVoteOptionIds(pollId, userId);

        // Повторный клик по тому же блюду — ничего не делаем, чек не тратим.
        if (currentVotes.contains(optionId)) {
            return VoteResult.ALREADY_VOTED;
        }

        // Первый выбор ИЛИ добавление ещё блюда — в обоих случаях нужен неистраченный чек.
        boolean firstChoice = currentVotes.isEmpty();
        boolean addMode = repository.isAddModeOn(pollId, userId);

        if (!firstChoice && !addMode) {
            return VoteResult.ALREADY_VOTED;   // уже выбрал, режим добавления не включён
        }

        // Гейт: есть ли оплаченный, ещё не потраченный чек.
        if (!payments.hasUnspentReceipt(pollId, userId)) {
            return VoteResult.PAYMENT_REQUIRED;
        }

        // Тратим ровно один чек на этот голос.
        if (!payments.spendOneReceipt(pollId, userId)) {
            return VoteResult.PAYMENT_REQUIRED;
        }

        repository.insertVote(pollId, userId, username, fullName, optionId);
        if (addMode) {
            repository.disableAddMode(pollId, userId);
        }
        refreshMessage(pollId);
        return VoteResult.SAVED;
    }

    @Transactional
    public VoteResult cancelVote(long pollId, long userId) {
        if (!repository.isPollActive(pollId)) {
            return VoteResult.POLL_CLOSED;
        }
        if (repository.findUserVoteOptionIds(pollId, userId).isEmpty()) {
            return VoteResult.ALREADY_VOTED;
        }
        repository.deleteAllUserVotes(pollId, userId);
        repository.disableAddMode(pollId, userId);
        payments.refundAll(pollId, userId);   // чеки уплачены — возвращаем право выбора
        refreshMessage(pollId);
        return VoteResult.CANCELLED;
    }

    @Transactional
    public VoteResult enableAddMode(long pollId, long userId) {
        if (!repository.isPollActive(pollId)) {
            return VoteResult.POLL_CLOSED;
        }
        if (repository.findUserVoteOptionIds(pollId, userId).isEmpty()) {
            return VoteResult.ALREADY_VOTED;
        }
        repository.enableAddMode(pollId, userId);
        // Права ещё нет — сразу честно предупреждаем, что нужен новый чек.
        if (!payments.hasUnspentReceipt(pollId, userId)) {
            return VoteResult.PAYMENT_REQUIRED;
        }
        return VoteResult.ADD_MODE_ENABLED;
    }

    // ============================================================ закрытие

    @Transactional
    public void closePoll(long chatId, long pollId) {
        repository.closePoll(pollId);
        Map<String, Object> poll = repository.findPoll(pollId);
        int messageId = ((Number) poll.get("message_id")).intValue();

        telegram.editMessageText(chatId, messageId,
                renderPoll(pollId) + "\n🔒 Голосование закрыто", EMPTY_KEYBOARD);
        telegram.sendMessage(chatId, "✅ Голосование закрыто.");
    }

    public void sendSummary(long chatId, long pollId) {
        repository.closePoll(pollId);

        List<Map<String, Object>> options = repository.findOptions(pollId);
        List<Map<String, Object>> votes = repository.findVotes(pollId);

        Map<Long, Long> countByOption = votes.stream().collect(Collectors.groupingBy(
                v -> ((Number) v.get("option_id")).longValue(), Collectors.counting()));

        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (Map<String, Object> option : options) {
            long optionId = ((Number) option.get("id")).longValue();
            long count = countByOption.getOrDefault(optionId, 0L);
            if (count > 0) {
                sb.append(option.get("text")).append(' ').append(count).append('\n');
                total += count;
            }
        }
        sb.append("Итого ").append(total).append('\n');
        sb.append("Азиз\n").append("Ибраимова 24/4");

        telegram.sendMessage(chatId, sb.toString());
    }

    // ============================================================ рендер

    private void refreshMessage(long pollId) {
        Map<String, Object> poll = repository.findPoll(pollId);
        long chatId = ((Number) poll.get("chat_id")).longValue();
        int messageId = ((Number) poll.get("message_id")).intValue();
        telegram.editMessageText(chatId, messageId, renderPoll(pollId), buildKeyboard(pollId));
    }

    private String renderPoll(long pollId) {
        Map<String, Object> poll = repository.findPoll(pollId);
        List<Map<String, Object>> options = repository.findOptions(pollId);
        List<Map<String, Object>> votes = repository.findVotes(pollId);

        Map<Long, List<Map<String, Object>>> grouped = votes.stream().collect(
                Collectors.groupingBy(v -> ((Number) v.get("option_id")).longValue()));

        StringBuilder sb = new StringBuilder();
        sb.append(poll.get("title")).append("\n\n");
        sb.append("Всего голосов: ").append(votes.size()).append("\n\n");

        for (Map<String, Object> option : options) {
            long optionId = ((Number) option.get("id")).longValue();
            List<Map<String, Object>> optionVotes = grouped.getOrDefault(optionId, List.of());
            if (optionVotes.isEmpty()) continue;

            sb.append("✅ ").append(option.get("text")).append(" (").append(optionVotes.size()).append(")\n");
            for (Map<String, Object> vote : optionVotes) {
                Object name = vote.get("full_name");
                sb.append("   - ").append(name != null ? name : "Без имени").append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private Map<String, Object> buildKeyboard(long pollId) {
        List<Map<String, Object>> options = repository.findOptions(pollId);
        List<Map<String, Object>> votes = repository.findVotes(pollId);

        Map<Long, Long> countByOption = votes.stream().collect(Collectors.groupingBy(
                v -> ((Number) v.get("option_id")).longValue(), Collectors.counting()));

        List<List<Map<String, String>>> rows = new ArrayList<>();

        for (Map<String, Object> option : options) {
            long optionId = ((Number) option.get("id")).longValue();
            // Текст кнопки — из сообщения, не из каталога. Каталог может быть пуст или недоступен.
            String label = (String) option.get("text");
            long count = countByOption.getOrDefault(optionId, 0L);
            if (count > 0) {
                label = label + " (" + count + ")";
            }
            rows.add(List.of(Map.of("text", label, "callback_data", "lunch_vote:" + pollId + ":" + optionId)));
        }

        rows.add(List.of(Map.of(
                "text", "🧾 Подтвердить оплату",
                "url", "https://t.me/" + telegram.username() + "?start=pay_" + pollId)));

        rows.add(List.of(
                Map.of("text", "➕ Добавить ещё блюдо", "callback_data", "lunch_add:" + pollId),
                Map.of("text", "❌ Отменить мои выборы", "callback_data", "lunch_cancel:" + pollId)));

        return Map.of("inline_keyboard", rows);
    }
}
