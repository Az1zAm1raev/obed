package com.example.lunchbot.all;

import com.example.lunchbot.dish.DishMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class LunchPollRepository {

    private final JdbcTemplate jdbc;

    public long createPoll(long chatId, String title, List<String> options) {
        Long pollId = jdbc.queryForObject("""
                INSERT INTO lunch_poll(chat_id, title)
                VALUES (?, ?)
                RETURNING id
                """, Long.class, chatId, title);

        for (int i = 0; i < options.size(); i++) {
            jdbc.update("""
                    INSERT INTO lunch_poll_option(poll_id, number, text, norm)
                    VALUES (?, ?, ?, ?)
                    """, pollId, i + 1, options.get(i), DishMatcher.normalize(options.get(i)));
        }

        return pollId;
    }

    public void closePoll(long pollId) {
        jdbc.update("UPDATE lunch_poll SET active = FALSE WHERE id = ?", pollId);
    }

    public boolean isPollActive(long pollId) {
        Boolean active = jdbc.queryForObject(
                "SELECT active FROM lunch_poll WHERE id = ?", Boolean.class, pollId);
        return Boolean.TRUE.equals(active);
    }

    /** Последний опрос в чате — активный или уже закрытый. Для /money после /close. */
    public Long findLastPollId(long chatId) {
        java.util.List<Long> r = jdbc.queryForList(
                "SELECT id FROM lunch_poll WHERE chat_id = ? ORDER BY created_at DESC LIMIT 1",
                Long.class, chatId);
        return r.isEmpty() ? null : r.get(0);
    }

    public Long findActivePollId(long chatId) {
        List<Long> result = jdbc.queryForList(
                "SELECT id FROM lunch_poll WHERE chat_id = ? AND active = TRUE ORDER BY created_at DESC LIMIT 1",
                Long.class, chatId);
        return result.isEmpty() ? null : result.get(0);
    }

    public void updateMessageId(long pollId, int messageId) {
        jdbc.update("UPDATE lunch_poll SET message_id = ? WHERE id = ?", messageId, pollId);
    }

    public Map<String, Object> findPoll(long pollId) {
        return jdbc.queryForMap("SELECT * FROM lunch_poll WHERE id = ?", pollId);
    }

    public List<Map<String, Object>> findOptions(long pollId) {
        return jdbc.queryForList("""
                SELECT id, number, text
                FROM lunch_poll_option
                WHERE poll_id = ?
                ORDER BY number
                """, pollId);
    }

    public List<Map<String, Object>> findVotes(long pollId) {
        return jdbc.queryForList("""
                SELECT v.option_id, v.full_name, v.username, v.user_id, v.free
                FROM lunch_poll_vote v
                WHERE v.poll_id = ?
                ORDER BY v.voted_at
                """, pollId);
    }

    public void insertVote(long pollId, long userId, String username, String fullName, long optionId) {
        insertVote(pollId, userId, username, fullName, optionId, false);
    }

    /** free = true — блюдо взято бесплатно (привилегия получателя), в /money не считается. */
    public void insertVote(long pollId, long userId, String username, String fullName,
                           long optionId, boolean free) {
        jdbc.update("""
                INSERT INTO lunch_poll_vote(poll_id, user_id, username, full_name, option_id, free)
                VALUES (?, ?, ?, ?, ?, ?)
                """, pollId, userId, username, fullName, optionId, free);
    }

    /** Сколько ПЛАТНЫХ блюд человек уже выбрал (бесплатное не считается). */
    public int countPaidVotes(long pollId, long userId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM lunch_poll_vote
                WHERE poll_id = ? AND user_id = ? AND NOT free
                """, Integer.class, pollId, userId);
        return n == null ? 0 : n;
    }

    /** Имена всех, кто когда-либо голосовал: user_id -> имя. Для /money, где нужен не id. */
    public java.util.Map<Long, String> findNames() {
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (user_id) user_id, full_name, username
                FROM lunch_poll_vote
                ORDER BY user_id, voted_at DESC
                """, rs -> {
            String n = rs.getString("full_name");
            if (n == null || n.isBlank()) n = rs.getString("username");
            out.put(rs.getLong("user_id"), n);
        });
        return out;
    }

    public List<Long> findUserVoteOptionIds(long pollId, long userId) {
        return jdbc.queryForList("""
            SELECT option_id
            FROM lunch_poll_vote
            WHERE poll_id = ? AND user_id = ?
            """, Long.class, pollId, userId);
    }

    public void deleteAllUserVotes(long pollId, long userId) {
        jdbc.update("""
            DELETE FROM lunch_poll_vote
            WHERE poll_id = ? AND user_id = ?
            """, pollId, userId);
    }

    public void closeAllActivePolls(long chatId) {
        jdbc.update("UPDATE lunch_poll SET active = FALSE WHERE chat_id = ? AND active = TRUE", chatId);
    }

    // --- режим "Добавить ещё блюдо" ---

    public boolean isAddModeOn(long pollId, long userId) {
        List<Long> result = jdbc.queryForList("""
            SELECT 1 FROM lunch_poll_add_mode WHERE poll_id = ? AND user_id = ?
            """, Long.class, pollId, userId);
        return !result.isEmpty();
    }

    public void enableAddMode(long pollId, long userId) {
        jdbc.update("""
            INSERT INTO lunch_poll_add_mode(poll_id, user_id)
            VALUES (?, ?)
            ON CONFLICT (poll_id, user_id) DO NOTHING
            """, pollId, userId);
    }

    public void disableAddMode(long pollId, long userId) {
        jdbc.update("""
            DELETE FROM lunch_poll_add_mode WHERE poll_id = ? AND user_id = ?
            """, pollId, userId);
    }

    // --- оценки блюд ---

    public record Rating(double avg, int count) {}

    /**
     * Общий балл по каждой опции опроса: option_id -> средняя оценка за ВСЕ опросы.
     * Блюдо из каталога сводится по dish_id (все написания), блюдо дня — по norm.
     */
    public Map<Long, Rating> findRatings(long pollId) {
        Map<Long, Rating> out = new java.util.HashMap<>();
        jdbc.query("""
                SELECT o.id, avg(r.score) AS avg, count(*) AS cnt
                FROM lunch_poll_option o
                JOIN lunch_poll_option ro
                  ON (o.dish_id IS NOT NULL AND ro.dish_id = o.dish_id)
                  OR (o.dish_id IS NULL AND ro.norm = o.norm)
                JOIN dish_rating r ON r.option_id = ro.id
                WHERE o.poll_id = ?
                GROUP BY o.id
                """, rs -> {
            out.put(rs.getLong("id"), new Rating(rs.getDouble("avg"), rs.getInt("cnt")));
        }, pollId);
        return out;
    }

    /** Блюда, которые человек взял в опросе (без повтора порций), и его оценка, если уже ставил. */
    public List<Map<String, Object>> findRateableOptions(long pollId, long userId) {
        return jdbc.queryForList("""
                SELECT o.id, o.text, r.score
                FROM lunch_poll_option o
                LEFT JOIN dish_rating r ON r.option_id = o.id AND r.user_id = ?
                WHERE o.poll_id = ?
                  AND EXISTS (SELECT 1 FROM lunch_poll_vote v WHERE v.option_id = o.id AND v.user_id = ?)
                ORDER BY o.number
                """, userId, pollId, userId);
    }

    public boolean hasVoteFor(long optionId, long userId) {
        return !jdbc.queryForList(
                "SELECT 1 FROM lunch_poll_vote WHERE option_id = ? AND user_id = ? LIMIT 1",
                Integer.class, optionId, userId).isEmpty();
    }

    public void upsertRating(long optionId, long userId, int score) {
        jdbc.update("""
                INSERT INTO dish_rating(option_id, user_id, score) VALUES (?, ?, ?)
                ON CONFLICT (option_id, user_id) DO UPDATE
                   SET score = EXCLUDED.score, rated_at = now()
                """, optionId, userId, score);
    }

    public Long findPollIdByOption(long optionId) {
        List<Long> r = jdbc.queryForList(
                "SELECT poll_id FROM lunch_poll_option WHERE id = ?", Long.class, optionId);
        return r.isEmpty() ? null : r.get(0);
    }
}