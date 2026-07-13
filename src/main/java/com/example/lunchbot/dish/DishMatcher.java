package com.example.lunchbot.dish;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Сопоставление строки меню с каталогом.
 *
 *   1) точное совпадение нормализованного ключа в dish_alias -> Exact
 *   2) levenshtein <= 2                                      -> Suggest (спросить админа)
 *   3) иначе                                                 -> None (блюдо дня)
 *
 * Порог 2 подобран по 13 реальным меню: ближайшие РАЗНЫЕ блюда
 * («босо лагман» / «гюро лагман») находятся на расстоянии 3, порог 3 их склеит.
 * Триграммный similarity() неприменим: «шамси гарнир» / «курица с гарниром» = 0.62
 * при 0.91 у «бозо лагман» / «босо лагман» — разделяющего порога не существует.
 */
@Component
@RequiredArgsConstructor
public class DishMatcher {

    static final int MAX_DISTANCE = 2;

    private final DishRepository repository;

    public sealed interface Match {
        record Exact(Dish dish) implements Match {}
        record Suggest(Dish dish, String norm) implements Match {}
        record None(String norm) implements Match {}
    }

    public Match match(String rawLine) {
        String norm = normalize(rawLine);
        if (norm.isBlank()) {
            return new Match.None(norm);
        }

        Optional<Dish> exact = repository.findByAlias(norm);
        if (exact.isPresent()) {
            return new Match.Exact(exact.get());
        }

        return repository.findNearest(norm)
                .<Match>map(d -> new Match.Suggest(d, norm))
                .orElseGet(() -> new Match.None(norm));
    }

    // ------------------------------------------------------- нормализация

    /**
     * Снимает всё, что различает одно блюдо в разные дни:
     * регистр, ё/е, эмодзи, U+200E, «+», звёздочки, двойные пробелы.
     *
     *   «Шамси  гарнир ‎»             -> «шамси гарнир»
     *   «Паста  🍝 карбонара»         -> «паста карбонара»
     *   «Мясо с костью +овощи +лаваш» -> «мясо с костью овощи лаваш»
     */
    public static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{IsCyrillic}\\p{IsLatin}0-9]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * Текст для кнопки: без эмодзи, без расшифровки в звёздочках.
     * «Ассорти *куриц котл мант* гарнир» -> «Ассорти гарнир»
     * Если после чистки пусто — возвращаем исходную строку.
     */
    public static String buttonLabel(String rawLine) {
        String s = rawLine
                .replaceAll("\\*[^*]*\\*?", " ")
                .replaceAll("[\\p{So}\\p{Cf}]", "")
                .replaceAll("\\s+", " ")
                .strip();
        return s.isBlank() ? rawLine.strip() : s;
    }
}
