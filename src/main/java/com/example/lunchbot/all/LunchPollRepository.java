package com.example.lunchbot.all;

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
                    INSERT INTO lunch_poll_option(poll_id, number, text)
                    VALUES (?, ?, ?)
                    """, pollId, i + 1, options.get(i));
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
                ON CONFLICT (poll_id, user_id, option_id) DO NOTHING
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
}