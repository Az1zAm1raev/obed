package com.example.lunchbot.receipt;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Проверка чека об оплате обеда.
 *
 * Стратегия (по убыванию надёжности):
 *   1) номер телефона получателя  — машинный, транслита нет, OCR портит редко
 *   2) имя получателя             — подтверждающий сигнал
 *   3) дата операции              — должна попадать в разрешённое окно
 *
 * Блоки отправителя вырезаются ДО проверок, иначе чек, где получатель является
 * плательщиком, ошибочно пройдёт проверку.
 *
 * Чек на СТАРЫЙ номер отклоняется, а не уходит на ручную проверку: деньги ушли
 * не тому человеку, и подтверждать тут нечего.
 */
@Component
@RequiredArgsConstructor
public class ReceiptVerifier {

    private static final ZoneId TZ = ZoneId.of("Asia/Bishkek");

    private final SettingsRepository settings;

    /** На сколько дней назад допускается чек (оплатил вчера вечером). */
    @Value("${lunch.receipt.back-days:1}")
    private int backDays;

    /** Скомпилированные шаблоны имён: ключ — само имя, чтобы после /recipient пересобрать. */
    private final Map<String, Pattern> namePatterns = new HashMap<>();

    // ---------------------------------------------------------------- метки

    private static final List<String> SENDER_LABELS = List.of(
            "реквизиты плательщика", "источник средств", "счет списания", "счёт списания",
            "paid from account", "плательщик", "отправитель", "sender", "payer", "от кого"
    );

    private static final List<String> RECIPIENT_LABELS = List.of(
            "реквизиты получателя", "details of transaction", "назначение платежа",
            "детали операции", "payment purpose", "получатель", "recipient",
            "комментарий", "описание", "реквизит", "requisite", "comment", "description"
    );

    /** dd.mm.yyyy | dd-mm-yy | dd/mm/yyyy — все банки пишут день первым. */
    private static final Pattern DATE = Pattern.compile("\\b(\\d{2})[.\\-/](\\d{2})[.\\-/](\\d{2,4})\\b");

    /** Идентификаторы для защиты от повторной отправки одного и того же чека. */
    private static final List<Pattern> TX_ID = List.of(
            Pattern.compile("(?:квитанция|receipt|чек)\\s*№?\\s*:?\\s*([A-Za-z0-9]{8,})",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("№\\s*:?\\s*([A-Za-z0-9]{8,})"),
            Pattern.compile("\\b([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\b"),
            Pattern.compile("(?:идентификатор\\s+транзакции|номер\\s+квитанции|номер\\s+чека)\\D{0,20}([A-Za-z0-9]{6,})",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
    );

    // ------------------------------------------------------------- результат

    public enum Status { APPROVED, REJECTED, MANUAL }

    public record Result(
            Status status,
            boolean phoneOk,
            boolean nameOk,
            boolean dateOk,
            LocalDate date,
            String txId,
            String reason
    ) {
        /** Разбор по пунктам — админ должен видеть, что именно бот не смог проверить. */
        public String breakdown() {
            return "Дата: " + (dateOk ? (date + " ✅") : "не та ❌") + "\n"
                    + "Получатель: " + (nameOk ? "распознан ✅" : "не найден ❌") + "\n"
                    + "Телефон: " + (phoneOk ? "совпал ✅" : "не найден ❌");
        }
    }

    // ------------------------------------------------------------- проверка

    public Result verify(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new Result(Status.MANUAL, false, false, false, null, null,
                    "Не удалось прочитать текст чека");
        }

        String phone = settings.recipientPhone();
        String name = settings.recipientName();
        String phoneSuffix = SettingsRepository.suffix(phone);

        Split split = splitSenderBlock(rawText);

        boolean phoneOk = split.nonSenderDigits().contains(phoneSuffix);

        String nameZone = split.recipientValues().isBlank() ? split.nonSender() : split.recipientValues();
        boolean nameOk = namePattern(name).matcher(normalize(nameZone)).find();

        LocalDate date = findAcceptableDate(rawText);
        boolean dateOk = date != null;

        String txId = findTxId(rawText);

        // ---- ни телефона, ни имени ----
        if (!phoneOk && !nameOk) {
            Optional<String> legacy = settings.legacyPhones().stream()
                    .filter(p -> split.nonSenderDigits().contains(SettingsRepository.suffix(p)))
                    .findFirst();

            String msg = legacy
                    .map(p -> "Вы оплатили на старый номер " + p + ". Деньги ушли не туда.\n"
                            + "Актуальный реквизит: " + name + ", " + phone)
                    .orElse("В чеке не найден получатель " + name + " (" + phone + ")");

            return new Result(Status.REJECTED, false, false, dateOk, date, txId, msg);
        }

        // ---- дата ----
        if (!dateOk) {
            LocalDate found = firstDate(rawText);
            String msg = found != null
                    ? "Дата в чеке: " + found + ", а нужен чек за сегодня"
                    : "В чеке не найдена дата операции";
            return new Result(Status.REJECTED, phoneOk, nameOk, false, found, txId, msg);
        }

        // ---- телефон найден: самый сильный признак ----
        if (phoneOk) {
            return new Result(Status.APPROVED, true, nameOk, true, date, txId, "OK");
        }

        // ---- имя есть, телефона нет: OCR мог смазать цифры ----
        return new Result(Status.MANUAL, false, true, true, date, txId,
                "Имя совпало, но не найден номер " + phone + " — нужна проверка вручную");
    }

    // -------------------------------------------------- разбор на метки

    private record Split(String nonSender, String nonSenderDigits, String recipientValues) {}

    /**
     * Строит пары «метка → значение». Значение либо в той же строке (MBank, Bakai),
     * либо на следующей (DemirBank, O!Деньги). Значения меток отправителя выбрасываются.
     */
    private Split splitSenderBlock(String text) {
        List<String> lines = text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();

        StringBuilder nonSender = new StringBuilder();
        StringBuilder recipient = new StringBuilder();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String low = normalize(line);

            String label = matchLabel(low, SENDER_LABELS);
            boolean sender = label != null;
            if (label == null) {
                label = matchLabel(low, RECIPIENT_LABELS);
            }

            if (label == null) {
                nonSender.append(line).append('\n');
                continue;
            }

            String value = line.substring(Math.min(label.length(), line.length()))
                    .replaceFirst("^[\\s:：]+", "").strip();

            if (value.isEmpty() && i + 1 < lines.size()) {
                value = lines.get(++i);
            }
            if (sender) {
                continue;   // блок отправителя игнорируем целиком
            }
            nonSender.append(value).append('\n');
            recipient.append(value).append('\n');
        }

        String ns = nonSender.toString();
        return new Split(ns, ns.replaceAll("\\D", ""), recipient.toString());
    }

    /** Метки ищем от самой длинной, чтобы «реквизиты плательщика» не схлопнулось в «реквизит». */
    private String matchLabel(String normalizedLine, List<String> labels) {
        for (String l : labels) {
            if (normalizedLine.startsWith(l)) {
                return l;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ имя

    /**
     * «Азиз Амираев» ловит: «Азиз А.», «АЗИЗ А.», «AZIZ A.», «Азиз Амираев», «Амираев Азиз».
     * Транслит гасится в normalize() до сравнения, поэтому латиница шаблон не ломает.
     */
    private synchronized Pattern namePattern(String fullName) {
        return namePatterns.computeIfAbsent(fullName, fn -> {
            String[] parts = normalize(fn).split(" ");
            if (parts.length < 2) {
                return Pattern.compile("\\b" + Pattern.quote(parts[0]) + "\\b",
                        Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE);
            }
            String first = Pattern.quote(parts[0]);
            String last = Pattern.quote(parts[1]);
            String initial = Pattern.quote(parts[1].substring(0, 1));

            return Pattern.compile(
                    "\\b" + first + "\\s+(" + initial + "|" + last + ")\\b"
                            + "|\\b" + last + "\\s+" + first + "\\b",
                    Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE);
        });
    }

    // ------------------------------------------------------ нормализация

    private static final Map<Character, Character> TRANSLIT = Map.ofEntries(
            Map.entry('a', 'а'), Map.entry('b', 'б'), Map.entry('c', 'с'), Map.entry('d', 'д'),
            Map.entry('e', 'е'), Map.entry('f', 'ф'), Map.entry('g', 'г'), Map.entry('h', 'н'),
            Map.entry('i', 'и'), Map.entry('k', 'к'), Map.entry('l', 'л'), Map.entry('m', 'м'),
            Map.entry('n', 'н'), Map.entry('o', 'о'), Map.entry('p', 'р'), Map.entry('r', 'р'),
            Map.entry('s', 'с'), Map.entry('t', 'т'), Map.entry('u', 'у'), Map.entry('v', 'в'),
            Map.entry('x', 'х'), Map.entry('y', 'у'), Map.entry('z', 'з')
    );

    /** lower-case, ё→е, латиница→кириллица, пунктуация→пробел. Гасит «BATYRKhAN B.» и «AZIZ A.» */
    private String normalize(String s) {
        String lower = s.toLowerCase(Locale.ROOT).replace('ё', 'е');
        StringBuilder sb = new StringBuilder(lower.length());
        for (char c : lower.toCharArray()) {
            sb.append(TRANSLIT.getOrDefault(c, c));
        }
        return sb.toString().replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    // -------------------------------------------------------------- даты

    private LocalDate findAcceptableDate(String text) {
        LocalDate today = LocalDate.now(TZ);
        LocalDate earliest = today.minusDays(backDays);

        for (LocalDate d : allDates(text)) {
            if (!d.isBefore(earliest) && !d.isAfter(today)) {
                return d;
            }
        }
        return null;
    }

    private LocalDate firstDate(String text) {
        List<LocalDate> all = allDates(text);
        return all.isEmpty() ? null : all.get(0);
    }

    private List<LocalDate> allDates(String text) {
        List<LocalDate> out = new ArrayList<>();
        Matcher m = DATE.matcher(text);
        while (m.find()) {
            try {
                int day = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                int year = Integer.parseInt(m.group(3));
                if (year < 100) {
                    year += 2000;               // DemirBank: 01.07.26
                }
                out.add(LocalDate.of(year, month, day));
            } catch (Exception ignored) {
                // не дата, а похожий на дату набор цифр
            }
        }
        return out;
    }

    // ---------------------------------------------------------- id чека

    private String findTxId(String text) {
        for (Pattern p : TX_ID) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }
}
