package com.example.lunchbot.receipt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Кто сколько платит и у кого одно блюдо бесплатно — из конфига, не из БД.
 * Меняется правкой переменных окружения без пересборки.
 *
 *   LUNCH_PRICE_REDUCED_USERS=8550001494,8765481879,1209071685   (платят 240)
 *
 * Бесплатное блюдо задаётся не здесь, а командой /recipient (recipient.id в БД).
 */
@Component
public class PriceBook {

    private final int defaultPrice;
    private final int reducedPrice;
    private final int canteenPrice;
    private final Set<Long> reducedUsers;

    public PriceBook(
            @Value("${lunch.price.default:250}") int defaultPrice,
            @Value("${lunch.price.reduced:240}") int reducedPrice,
            @Value("${lunch.price.canteen:240}") int canteenPrice,
            @Value("${lunch.price.reduced-users:}") String reducedIds) {
        this.defaultPrice = defaultPrice;
        this.reducedPrice = reducedPrice;
        this.canteenPrice = canteenPrice;
        this.reducedUsers = parseIds(reducedIds);
    }

    private static Set<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toSet());
    }

    /** Сколько человек платит за блюдо: 240 для льготников, иначе 250. */
    public int priceFor(long userId) {
        return reducedUsers.contains(userId) ? reducedPrice : defaultPrice;
    }

    /** Цена, которую берёт столовая за порцию (одинаковая для всех). */
    public int canteenPrice() {
        return canteenPrice;
    }

}
