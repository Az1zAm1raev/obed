package com.example.lunchbot.receipt;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Настройки в БД. Читаются на каждой проверке чека — это один SELECT
 * несколько раз в день, кэш не нужен и только мешал бы после /recipient.
 */
@Repository
@RequiredArgsConstructor
public class SettingsRepository {

    private final JdbcTemplate jdbc;

    public String get(String key) {
        List<String> r = jdbc.queryForList("SELECT value FROM app_setting WHERE key = ?", String.class, key);
        return r.isEmpty() ? null : r.get(0);
    }

    public void set(String key, String value, long adminId) {
        jdbc.update("""
                INSERT INTO app_setting(key, value, updated_by) VALUES (?, ?, ?)
                ON CONFLICT (key) DO UPDATE
                   SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = now()
                """, key, value, adminId);
    }

    // ------------------------------------------------------------ реквизит

    public String recipientPhone() {
        return get("recipient.phone");
    }

    public String recipientName() {
        return get("recipient.name");
    }

    /** Основная группа (задаётся /setmain). Из лички команды считают по ней. */
    public Long mainChatId() {
        String v = get("main.chat.id");
        if (v == null || v.isBlank()) return null;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return null; }
    }

    public void setMainChatId(long chatId, long adminId) {
        set("main.chat.id", String.valueOf(chatId), adminId);
    }

    /** id получателя — он же имеет право на одно бесплатное блюдо. Может быть не задан. */
    public Long recipientId() {
        String v = get("recipient.id");
        if (v == null || v.isBlank()) return null;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return null; }
    }

    // QR оплаты — Telegram file_id картинки.
    public String qrFileId() {
        return get("payment.qr");
    }

    public void setQr(String fileId, long adminId) {
        set("payment.qr", fileId, adminId);
    }

    /** Номера, на которые платить больше нельзя. Чек на них отклоняется. */
    public List<String> legacyPhones() {
        String raw = get("recipient.phone.legacy");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Меняет реквизит. Прежний номер переезжает в legacy — не для того,
     * чтобы его засчитывать, а чтобы объяснить человеку, куда ушли деньги.
     */
    public void changeRecipient(String phone, String name, Long recipientId, long adminId) {
        if (recipientId != null) {
            set("recipient.id", String.valueOf(recipientId), adminId);
        }
        String previous = recipientPhone();

        if (previous != null && !previous.equals(phone)) {
            String legacy = Stream.concat(legacyPhones().stream(), Stream.of(previous))
                    .distinct()
                    .reduce((a, b) -> a + "," + b)
                    .orElse(previous);
            set("recipient.phone.legacy", legacy, adminId);
        }
        set("recipient.phone", phone, adminId);
        set("recipient.name", name, adminId);
    }

    /** Добавить номер в список старых (на которые платить больше нельзя). */
    public void appendLegacyPhone(String phone, long adminId) {
        java.util.List<String> legacy = new java.util.ArrayList<>(legacyPhones());
        if (!legacy.contains(phone)) legacy.add(phone);
        set("recipient.phone.legacy", String.join(",", legacy), adminId);
    }

    /** Последние 9 цифр: гасит +996 / 996 / 0 в начале. */
    public static String suffix(String phone) {
        String digits = phone.replaceAll("\\D", "");
        return digits.length() > 9 ? digits.substring(digits.length() - 9) : digits;
    }
}