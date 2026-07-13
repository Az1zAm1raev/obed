package com.example.lunchbot;

import com.example.lunchbot.all.LunchPollRequest;
import com.example.lunchbot.all.LunchPollRequestParser;
import com.example.lunchbot.dish.DishMatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Проверки на реальных меню и реальных чеках. БД не требуется. */
class ParsingTest {

    @Test
    void parsesRealMenu() {
        String text = """
                /lunch\s

                Здравствуйте 🌹🌹🌹

                Мясная жаровня с картошкой по деревенски

                Бифштекс гарнир\s

                Шамси гарнир\s

                Ассорти *куриц котлет мант* гарнир

                Том ям 🍠 с курицей 🍚 рис
                """;

        LunchPollRequest r = LunchPollRequestParser.parse(text);

        assertEquals(5, r.options().size());
        assertEquals("Мясная жаровня с картошкой по деревенски", r.options().get(0));
        assertEquals("Том ям 🍠 с курицей 🍚 рис", r.options().get(4));
    }

    @Test
    void dropsGreetingAndEmojiOnlyLines() {
        String text = "/lunch\nЗдравствуйте 🌹🌹🌹\n🌹🌹\nБосо лагман\nГюро лагман";
        assertEquals(2, LunchPollRequestParser.parse(text).options().size());
    }

    @Test
    void rejectsEmptyMenu() {
        assertTrue(LunchPollRequestParser.parse("/lunch").options().isEmpty());
    }

    // -------------------------------------------------------- нормализация

    @Test
    void normalizeCollapsesRealVariants() {
        assertEquals("шамси гарнир", DishMatcher.normalize("Шамси  гарнир \u200e"));
        assertEquals("паста карбонара", DishMatcher.normalize("Паста  🍝 карбонара"));
        assertEquals("мясо с костью овощи лаваш", DishMatcher.normalize("Мясо с костью +овощи +лаваш"));
        assertEquals("пельмени в кляре соус тар тар", DishMatcher.normalize("Пельмени 🥟 в кляре соус тар тар"));
    }

    @Test
    void normalizeKeepsDifferentDishesDifferent() {
        assertNotEquals(DishMatcher.normalize("Босо лагман"), DishMatcher.normalize("Гюро лагман"));
        assertNotEquals(DishMatcher.normalize("Бифштекс гарнир"), DishMatcher.normalize("Шамси гарнир"));
    }

    @Test
    void buttonLabelStripsAsterisksAndEmoji() {
        assertEquals("Ассорти гарнир", DishMatcher.buttonLabel("Ассорти *куриц котл мант* гарнир"));
        assertEquals("Том ям с курицей рис", DishMatcher.buttonLabel("Том ям 🍠 с курицей 🍚 рис"));
    }
}
