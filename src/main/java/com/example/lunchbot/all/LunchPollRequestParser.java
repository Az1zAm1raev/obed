package com.example.lunchbot.all;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Разбор сообщения /lunch.
 *
 * ИНВАРИАНТ: парсер работает только с текстом сообщения.
 * Он ничего не знает про каталог блюд, БД и фото. Опрос создаётся всегда.
 *
 * Формат реальных сообщений (проверено на 13 меню):
 *   /lunch
 *   Здравствуйте 🌹🌹🌹      <- приветствие, выбрасываем
 *                            <- пустые строки между блюдами
 *   Босо лагман              <- хвостовые пробелы
 *   Ассорти *куриц котл мант* гарнир
 *   Том ям 🍠 с курицей 🍚 рис
 *   Шамси  гарнир ‎          <- невидимый U+200E в конце
 */
public final class LunchPollRequestParser {

    private static final Pattern GREETING = Pattern.compile(
            "^(здравствуйте|здравствуй|доброе|добрый|привет|салам|assalam|hello)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Строка считается блюдом, только если в ней есть хоть одна буква или цифра. */
    private static final Pattern HAS_CONTENT = Pattern.compile("[\\p{L}\\p{N}]");

    /** Невидимые символы: U+200E/200F (метки направления), U+FEFF, мягкий перенос. */
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B-\\u200F\\uFEFF\\u00AD]");

    private LunchPollRequestParser() {}

    public static LunchPollRequest parse(String text) {
        String body = text.replaceFirst("(?i)^\\s*/lunch(@\\w+)?", "");

        List<String> options = new ArrayList<>();

        for (String line : body.split("\\R")) {          // \R = любой перевод строки
            String s = INVISIBLE.matcher(line).replaceAll("").strip();

            if (s.isEmpty()) continue;
            if (GREETING.matcher(s).find()) continue;    // «Здравствуйте 🌹🌹🌹»
            if (!HAS_CONTENT.matcher(s).find()) continue; // строка из одних эмодзи

            options.add(s);
        }

        // Заголовок опроса не берём из сообщения — он там не задаётся.
        return new LunchPollRequest("🍽 Обед на сегодня", options);
    }
}
