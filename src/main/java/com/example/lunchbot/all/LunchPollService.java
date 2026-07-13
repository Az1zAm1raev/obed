package com.example.lunchbot.all;

import com.example.lunchbot.dish.CatalogHandler;
import com.example.lunchbot.receipt.PaymentService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PostConstruct;

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
    private final com.example.lunchbot.receipt.PriceBook priceBook;

    /**
     * Ссылка на сообщение опроса. Для супергрупп: https://t.me/c/<internal>/<msgId>,
     * где internal = chat_id без префикса -100. Для обычных групп ссылки нет — вернём null,
     * тогда PaymentService просто не покажет кнопку.
     */
    @PostConstruct
    void wirePollLink() {
        payments.setPollGate(repository::isPollActive);
        payments.setPollLinkResolver(pollId -> {
            try {
                Map<String, Object> poll = repository.findPoll(pollId);
                long chatId = ((Number) poll.get("chat_id")).longValue();
                Object msgObj = poll.get("message_id");
                if (msgObj == null) return null;
                long msgId = ((Number) msgObj).longValue();
                String s = String.valueOf(chatId);
                if (s.startsWith("-100")) {
                    return "https://t.me/c/" + s.substring(4) + "/" + msgId;
                }
                return null;   // обычная группа — прямых ссылок нет
            } catch (Exception e) {
                return null;
            }
        });
    }

    public Long findActivePollId(long chatId) {
        return repository.findActivePollId(chatId);
    }

    public Long findLastPollId(long chatId) {
        return repository.findLastPollId(chatId);
    }

    /** Текст для всплывашки, когда денег на балансе не хватает на блюдо. */
    public String shortfallMessage(long pollId, long userId) {
        int paidDishes = repository.countPaidVotes(pollId, userId);
        int price = payments.priceFor(userId);
        int balance = payments.balance(pollId, userId, paidDishes);
        int need = price - balance;
        if (balance <= 0) {
            return "🧾 Нужен чек. Блюдо стоит " + price + " сом.\n"
                    + "Нажмите «Подтвердить оплату» и пришлите PDF-чек в личку.";
        }
        return "🧾 На балансе " + balance + " сом, блюдо стоит " + price + ".\n"
                + "Не хватает " + need + ". Пришлите ещё чек — остаток учтётся.";
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

        boolean firstChoice = currentVotes.isEmpty();
        boolean addMode = repository.isAddModeOn(pollId, userId);

        if (!firstChoice && !addMode) {
            return VoteResult.ALREADY_VOTED;   // уже выбрал, режим добавления не включён
        }

        // Первое блюдо привилегированного пользователя — бесплатно, без чека.
        if (firstChoice && payments.hasFreeDish(userId)) {
            repository.insertVote(pollId, userId, username, fullName, optionId, true);
            refreshMessage(pollId);
            return VoteResult.SAVED;
        }

        // Денежный баланс: хватает ли на ещё одно платное блюдо.
        int paidDishes = repository.countPaidVotes(pollId, userId);
        if (!payments.canAfford(pollId, userId, paidDishes)) {
            return VoteResult.PAYMENT_REQUIRED;   // сообщение с суммой соберём в worker
        }

        repository.insertVote(pollId, userId, username, fullName, optionId, false);
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
        // Баланс восстановится сам: он считается как оплачено − число блюд.
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
        int paidDishes = repository.countPaidVotes(pollId, userId);
        if (!payments.canAfford(pollId, userId, paidDishes)) {
            return VoteResult.PAYMENT_REQUIRED;
        }
        return VoteResult.ADD_MODE_ENABLED;
    }

    // ============================================================ закрытие

    @Transactional
    public void closePoll(long chatId, long pollId) {
        repository.closePoll(pollId);
        payments.clearPendingByPoll(pollId);
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
        sb.append("Батырхан\n").append("Ибраимова 24/4");

        telegram.sendMessage(chatId, sb.toString());
    }

    /**
     * Денежный расчёт — ТОЛЬКО в личку админу, чтобы остальные не видели.
     * Бесплатные блюда (привилегия) исключены из обеих строк: столовая за них
     * не берёт, человек не платит.
     */
    public void sendMoney(long adminChatId, long pollId) {
        List<Map<String, Object>> votes = repository.findVotes(pollId);

        // по каждому пользователю: сколько платных блюд и его цена
        Map<Long, Integer> paidDishesByUser = new java.util.HashMap<>();
        Map<Long, String> nameByUser = new java.util.HashMap<>();
        int freeCount = 0;

        for (Map<String, Object> v : votes) {
            long userId = ((Number) v.get("user_id")).longValue();
            nameByUser.putIfAbsent(userId, String.valueOf(v.getOrDefault("full_name", "?")));
            if (Boolean.TRUE.equals(v.get("free"))) {
                freeCount++;
            } else {
                paidDishesByUser.merge(userId, 1, Integer::sum);
            }
        }

        int paidCount = paidDishesByUser.values().stream().mapToInt(Integer::intValue).sum();
        int canteen = paidCount * priceBook.canteenPrice();

        // реально собрано = суммы чеков
        Map<Long, Integer> paidMoney = payments.paidByUser(pollId);
        int collected = paidMoney.values().stream().mapToInt(Integer::intValue).sum();

        // переплаты и неиспользованные платежи
        StringBuilder over = new StringBuilder();   // взяли меньше, чем оплатили
        StringBuilder unused = new StringBuilder(); // оплатили, но не выбрали
        for (Map.Entry<Long, Integer> e : paidMoney.entrySet()) {
            long uid = e.getKey();
            int paid = e.getValue();
            int dishes = paidDishesByUser.getOrDefault(uid, 0);
            int price = priceBook.priceFor(uid);
            int spent = dishes * price;
            int left = paid - spent;
            String who = nameByUser.getOrDefault(uid, String.valueOf(uid));
            if (dishes == 0 && paid > 0) {
                unused.append("  ").append(who).append(" — ").append(paid).append(" (вернуть полностью)\n");
            } else if (left > 0) {
                over.append("  ").append(who).append(" — ").append(left)
                        .append(" (взял ").append(dishes).append(", оплатил ").append(paid).append(")\n");
            }
        }

        int remainder = collected - canteen;

        StringBuilder text = new StringBuilder("💰 Расчёт (только для вас)\n\n");
        text.append("Порций: ").append(paidCount).append(" оплаченных");
        if (freeCount > 0) text.append(" + ").append(freeCount).append(" бесплатное");
        text.append("\n");
        text.append("Столовой: ").append(paidCount).append(" × ").append(priceBook.canteenPrice())
                .append(" = ").append(canteen).append("\n");
        text.append("Собрано: ").append(collected).append("\n");
        text.append("Остаток вам: ").append(remainder).append("\n");

        if (over.length() > 0) {
            text.append("\n⚠️ Переплатили:\n").append(over);
        }
        if (unused.length() > 0) {
            text.append("\n💸 Оплатили, но не выбрали блюдо:\n").append(unused);
        }

        telegram.sendMessage(adminChatId, text.toString());
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