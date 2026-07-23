package com.example.lunchbot.dish;

import com.example.lunchbot.all.TelegramClient;
import com.example.lunchbot.receipt.SettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Каталог постоянных блюд: /catalog, /dish, /menu и все callback'и.
 *
 * Каталог НИКОГДА не влияет на создание опроса. Он только:
 *   - проставляет dish_id у опций, где совпадение точное;
 *   - складывает похожие названия в dish_question для подтверждения админом;
 *   - хранит фото.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogHandler {

    private static final String ADD_DISH_NAME  = "ADD_DISH_NAME";
    private static final String ADD_DISH_PHOTO = "ADD_DISH_PHOTO";
    private static final String SET_PHOTO      = "SET_PHOTO";
    private static final String ADD_ALIAS      = "ADD_ALIAS";
    private static final String MERGE_TARGET   = "MERGE_TARGET";
    private static final String LUNCH_MENU = "LUNCH_MENU";
    private static final String REC_NAME  = "REC_NAME";
    private static final String REC_PHONE = "REC_PHONE";
    private static final String REC_ID    = "REC_ID";

    private final TelegramClient telegram;
    private final DishRepository repository;
    private final DishMatcher matcher;
    private final SettingsRepository settings;

    // ============================================================ /catalog

    public void showCatalog(long chatId) {
        List<Dish> dishes = repository.catalog();

        StringBuilder sb = new StringBuilder("📖 Каталог (" + dishes.size() + ")\n\n");
        List<List<Map<String, String>>> rows = new ArrayList<>();

        for (Dish d : dishes) {
            sb.append(d.hasPhoto() ? "📷 " : "⬜ ").append(d.name()).append('\n');
            rows.add(List.of(Map.of(
                    "text", (d.hasPhoto() ? "📷 " : "⬜ ") + d.name(),
                    "callback_data", "dish:" + d.id())));
        }
        if (dishes.isEmpty()) {
            sb.append("Пусто. Добавьте первое блюдо.\n");
        }
        rows.add(List.of(Map.of("text", "➕ Добавить блюдо", "callback_data", "dish_add")));

        telegram.sendMessage(chatId, sb.toString(), Map.of("inline_keyboard", rows));
    }

    public void showCard(long chatId, long dishId) {
        Optional<Dish> found = repository.findById(dishId);
        if (found.isEmpty()) {
            telegram.sendMessage(chatId, "Блюдо не найдено.");
            return;
        }
        Dish d = found.get();
        List<String> aliases = repository.aliasesOf(dishId);

        String text = d.name() + "\n\n"
                + "Написания: " + String.join(", ", aliases) + "\n"
                + "Фото: " + (d.hasPhoto() ? "есть" : "нет");

        Map<String, Object> kb = Map.of("inline_keyboard", List.of(
                List.of(
                        Map.of("text", d.hasPhoto() ? "📷 Заменить фото" : "📷 Добавить фото",
                                "callback_data", "dish_photo:" + dishId),
                        Map.of("text", "🔗 Написание", "callback_data", "dish_alias:" + dishId)),
                List.of(
                        Map.of("text", "🔀 Это дубль", "callback_data", "dish_merge:" + dishId),
                        Map.of("text", "🗑 Удалить", "callback_data", "dish_del:" + dishId)),
                List.of(Map.of("text", "◀️ Назад", "callback_data", "dish_back"))));

        if (d.hasPhoto()) {
            telegram.sendPhoto(chatId, d.photoFileId(), null);
        }
        telegram.sendMessage(chatId, text, kb);
    }

    // ============================================================ /menu

    /** Фото блюд текущего опроса. Рендерится в момент вызова — алиасы, подтверждённые позже, учитываются. */
    public void showMenuPhotos(long chatId, Long pollId) {
        if (pollId == null) {
            telegram.sendMessage(chatId, "Сейчас нет активного голосования.");
            return;
        }
        List<Map<String, Object>> entries = repository.menuEntries(pollId);
        if (entries.isEmpty()) {
            telegram.sendMessage(chatId, "В опросе нет блюд.");
            return;
        }

        // Название, сразу под ним фото — и так по каждому блюду.
        // Альбомом не шлём: там подписи видны только при тапе на фото.
        for (int i = 0; i < entries.size(); i++) {
            Map<String, Object> e = entries.get(i);
            String name = String.valueOf(e.get("name"));
            Object photo = e.get("photo");

            String caption = (i + 1) + ". " + name;
            if (photo != null) {
                telegram.sendPhoto(chatId, String.valueOf(photo), caption);
            } else {
                telegram.sendMessage(chatId, caption + "\n(фото нет)");
            }
        }
    }

    // ============================================================ callbacks

    /** @return текст для answerCallbackQuery, либо null если ничего не сделали */
    public String handleCallback(long userId, long chatId, String data) {
        if (data.equals("dish_back")) {
            showCatalog(chatId);
            return null;
        }
        if (data.equals("dish_add")) {
            repository.setState(userId, chatId, ADD_DISH_NAME, null);
            telegram.sendMessage(chatId, "Пришлите название блюда одним сообщением.\n/cancel — отмена");
            return null;
        }

        String[] parts = data.split(":");
        long id = Long.parseLong(parts[1]);

        switch (parts[0]) {
            case "dish" -> showCard(chatId, id);

            case "dish_photo" -> {
                repository.setState(userId, chatId, SET_PHOTO, id);
                telegram.sendMessage(chatId, "Пришлите фото блюда.\n/cancel — отмена");
            }
            case "dish_alias" -> {
                repository.setState(userId, chatId, ADD_ALIAS, id);
                telegram.sendMessage(chatId, "Пришлите ещё одно написание этого блюда.\n/cancel — отмена");
            }
            case "dish_merge" -> {
                repository.setState(userId, chatId, MERGE_TARGET, id);
                telegram.sendMessage(chatId,
                        "Это блюдо — дубль. Пришлите название ОСНОВНОГО блюда, к которому его присоединить.\n/cancel — отмена");
            }
            case "dish_del" -> {
                repository.deactivate(id);
                showCatalog(chatId);
                return "Удалено";
            }

            // --- ответы на вопросы по несовпадениям ---
            case "q_yes" -> {
                return resolveQuestionYes(userId, id);
            }
            case "q_no" -> {
                repository.resolveQuestion(id);
                return "Оставлено как блюдо дня";
            }
            default -> {
                return null;
            }
        }
        return null;
    }

    private String resolveQuestionYes(long adminId, long questionId) {
        Optional<DishRepository.Question> q = repository.findQuestion(questionId);
        if (q.isEmpty()) {
            return "Вопрос уже закрыт";
        }
        DishRepository.Question question = q.get();

        repository.addAlias(question.normText(), question.suggestId(), adminId);
        repository.setOptionDish(question.optionId(), question.suggestId());
        repository.resolveQuestion(questionId);

        return "Написание сохранено";
    }

    // ============================================================ FSM: текст

    /**
     * @return true, если сообщение поглощено диалогом каталога
     *
     * Команды никогда не поглощаются: диалог живёт в группе, и «/close» посреди
     * добавления блюда должен закрыть опрос, а не стать названием блюда.
     */
    /** /lunch без меню — ждём список блюд отдельным сообщением. */
    public void startLunchDialog(long userId, long chatId) {
        repository.setState(userId, chatId, LUNCH_MENU, null);
        telegram.sendMessage(chatId,
                "Пришлите меню — каждое блюдо с новой строки.\n/cancel — отмена");
    }

    /** Куда отдать собранное меню. Ставится из LunchPollService. */
    private java.util.function.BiConsumer<Long, String> lunchMenuHandler;
    public void setLunchMenuHandler(java.util.function.BiConsumer<Long, String> h) {
        this.lunchMenuHandler = h;
    }

    /** Запуск пошагового ввода реквизита: имя → телефон → id. */
    public void startRecipientDialog(long userId, long chatId) {
        repository.setState(userId, chatId, REC_NAME, null);
        telegram.sendMessage(chatId,
                "Смена реквизита. Введите имя получателя (например: Азиз Амираев).\n/cancel — отмена");
    }

    public boolean handleText(long userId, long chatId, String text) {
        Optional<DishRepository.State> found = repository.findState(userId);
        if (found.isEmpty()) {
            return false;
        }
        if (text.equals("/cancel")) {
            repository.clearState(userId);
            telegram.sendMessage(chatId, "Отменено.");
            return true;
        }
        if (text.startsWith("/")) {
            repository.clearState(userId);
            return false;   // отдаём команде, диалог сбрасываем
        }

        DishRepository.State st = found.get();

        switch (st.state()) {
            case ADD_DISH_NAME -> {
                if (repository.findByAlias(DishMatcher.normalize(text)).isPresent()) {
                    telegram.sendMessage(chatId, "Такое блюдо уже есть в каталоге.");
                    repository.clearState(userId);
                    return true;
                }
                long dishId = repository.create(text, userId);
                repository.setState(userId, chatId, ADD_DISH_PHOTO, dishId);
                telegram.sendMessage(chatId, "Добавлено. Теперь пришлите фото — или /cancel, если фото пока нет.");
            }
            case ADD_ALIAS -> {
                repository.addAlias(DishMatcher.normalize(text), st.payload(), userId);
                repository.clearState(userId);
                telegram.sendMessage(chatId, "🔗 Написание сохранено.");
                showCard(chatId, st.payload());
            }
            case MERGE_TARGET -> {
                Optional<Dish> target = repository.findByAlias(DishMatcher.normalize(text));
                if (target.isEmpty()) {
                    telegram.sendMessage(chatId, "Не нашёл такое блюдо. Пришлите точное название или /cancel.");
                    return true;
                }
                if (target.get().id() == st.payload()) {
                    telegram.sendMessage(chatId, "Это то же самое блюдо.");
                    return true;
                }
                repository.merge(st.payload(), target.get().id());
                repository.clearState(userId);
                telegram.sendMessage(chatId, "🔀 Объединено с «" + target.get().name() + "».");
                showCatalog(chatId);
            }
            case LUNCH_MENU -> {
                repository.clearState(userId);
                if (lunchMenuHandler != null) {
                    lunchMenuHandler.accept(chatId, text);
                }
            }
            case REC_NAME -> {
                if (text.length() < 2) {
                    telegram.sendMessage(chatId, "Имя слишком короткое. Введите имя и фамилию.");
                    return true;
                }
                settings.set("recipient.name", text.strip(), userId);
                repository.setState(userId, chatId, REC_PHONE, null);
                telegram.sendMessage(chatId, "Имя принято. Теперь введите номер телефона (996XXXXXXXXX).");
            }
            case REC_PHONE -> {
                String phone = text.replaceAll("[^0-9+]", "");
                if (phone.replaceAll("\\D", "").length() < 9) {
                    telegram.sendMessage(chatId, "Не похоже на номер. Введите ещё раз, например 996708760011.");
                    return true;
                }
                String prevPhone = settings.recipientPhone();
                settings.set("recipient.phone", phone.replaceAll("\\D", ""), userId);
                if (prevPhone != null && !prevPhone.equals(phone.replaceAll("\\D", ""))) {
                    settings.appendLegacyPhone(prevPhone, userId);
                }
                repository.setState(userId, chatId, REC_ID, null);
                telegram.sendMessage(chatId,
                        "Номер принят. Теперь введите Telegram id получателя "
                                + "(тот, у кого одно блюдо бесплатно).\n"
                                + "Если id не нужен — напишите «-».");
            }
            case REC_ID -> {
                String v = text.strip();
                if (!v.equals("-")) {
                    if (!v.matches("\\d{6,}")) {
                        telegram.sendMessage(chatId, "id — это число. Введите ещё раз или «-».");
                        return true;
                    }
                    settings.set("recipient.id", v, userId);
                }
                repository.clearState(userId);
                Long rid = settings.recipientId();
                telegram.sendMessage(chatId,
                        "✅ Реквизит обновлён:\n"
                                + settings.recipientName() + " · " + settings.recipientPhone()
                                + (rid != null ? " · id " + rid : ""));
            }
            case ADD_DISH_PHOTO, SET_PHOTO ->
                    telegram.sendMessage(chatId, "Жду фото, а не текст. /cancel — отмена");

            default -> repository.clearState(userId);
        }
        return true;
    }

    // ============================================================ FSM: фото

    /** @return true, если фото поглощено каталогом (значит, это не чек) */
    public boolean handlePhoto(long userId, long chatId, String caption, String fileId, String uniqueId) {
        // /dish Босо лагман  + фото
        if (caption != null && caption.strip().toLowerCase().startsWith("/dish ")) {
            String name = caption.strip().substring(6).strip();
            Optional<Dish> dish = repository.findByAlias(DishMatcher.normalize(name));
            if (dish.isEmpty()) {
                telegram.sendMessage(chatId, "❌ «" + name + "» нет в каталоге. Добавьте через /catalog.");
                return true;
            }
            repository.setPhoto(dish.get().id(), fileId, uniqueId);
            telegram.sendMessage(chatId, "📷 Фото для «" + dish.get().name() + "» сохранено.");
            return true;
        }

        Optional<DishRepository.State> found = repository.findState(userId);
        if (found.isEmpty()) {
            return false;
        }
        DishRepository.State st = found.get();
        if (!st.state().equals(ADD_DISH_PHOTO) && !st.state().equals(SET_PHOTO)) {
            return false;
        }

        repository.setPhoto(st.payload(), fileId, uniqueId);
        repository.clearState(userId);
        telegram.sendMessage(chatId, "📷 Фото сохранено.");
        showCard(chatId, st.payload());
        return true;
    }

    // ============================================================ обогащение

    /**
     * Вызывается ПОСЛЕ создания опроса. Любая ошибка здесь не должна ломать опрос.
     * Возвращает список вопросов для админа.
     */
    public void enrich(long pollId, List<Long> optionIds, List<String> rawLines, long chatId) {
        for (int i = 0; i < rawLines.size(); i++) {
            String raw = rawLines.get(i);
            long optionId = optionIds.get(i);

            switch (matcher.match(raw)) {
                case DishMatcher.Match.Exact(Dish dish) ->
                        repository.setOptionDish(optionId, dish.id());

                case DishMatcher.Match.Suggest(Dish dish, String norm) -> {
                    long qId = repository.addQuestion(pollId, optionId, raw, norm, dish.id());
                    telegram.sendMessage(chatId,
                            "❓ «" + raw.strip() + "» — это «" + dish.name() + "»?",
                            Map.of("inline_keyboard", List.of(List.of(
                                    Map.of("text", "✅ Да, то же блюдо", "callback_data", "q_yes:" + qId),
                                    Map.of("text", "🚫 Блюдо дня", "callback_data", "q_no:" + qId)))));
                }
                case DishMatcher.Match.None ignored -> {
                    // блюдо дня, dish_id остаётся NULL
                }
            }
        }
    }
}