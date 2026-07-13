package com.example.lunchbot.receipt;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Баланс чеков. Один одобренный чек = право на один голос.
 * Голос "тратит" чек (spent = TRUE). Отмена голоса возвращает чек в оборот.
 */
@Repository
@RequiredArgsConstructor
public class PaymentRepository {

    private final JdbcTemplate jdbc;

    // ------------------------------------------------ сохранение чека

    /**
     * @return id сохранённого чека, либо null если tx_id уже использован (дубль).
     */
    public Long saveReceipt(long pollId, long userId, String status, String fileId, String txId) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO lunch_receipt(poll_id, user_id, status, file_id, tx_id)
                    VALUES (?, ?, ?, ?, ?)
                    RETURNING id
                    """, Long.class, pollId, userId, status, fileId, txId);
        } catch (DuplicateKeyException e) {
            return null;   // ux_receipt_tx: этот чек уже засчитан
        }
    }

    // ------------------------------------------------ баланс

    /** Есть ли неистраченный одобренный чек — то есть право проголосовать. */
    public boolean hasUnspentReceipt(long pollId, long userId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM lunch_receipt
                WHERE poll_id = ? AND user_id = ? AND status = 'APPROVED' AND NOT spent
                """, Integer.class, pollId, userId);
        return n != null && n > 0;
    }

    /** Потратить один чек на голос. @return true, если чек нашёлся и списан. */
    public boolean spendOneReceipt(long pollId, long userId) {
        int updated = jdbc.update("""
                UPDATE lunch_receipt SET spent = TRUE
                WHERE id = (
                    SELECT id FROM lunch_receipt
                    WHERE poll_id = ? AND user_id = ? AND status = 'APPROVED' AND NOT spent
                    ORDER BY created_at
                    LIMIT 1
                )
                """, pollId, userId);
        return updated > 0;
    }

    /** Вернуть все чеки человека в оборот — при отмене голосов. */
    public void refundAll(long pollId, long userId) {
        jdbc.update("""
                UPDATE lunch_receipt SET spent = FALSE
                WHERE poll_id = ? AND user_id = ? AND status = 'APPROVED'
                """, pollId, userId);
    }

    // ------------------------------------------------ ручная проверка

    public void approveReceipt(long receiptId) {
        jdbc.update("UPDATE lunch_receipt SET status = 'APPROVED' WHERE id = ?", receiptId);
    }

    public void deleteReceipt(long receiptId) {
        jdbc.update("DELETE FROM lunch_receipt WHERE id = ?", receiptId);
    }

    // ------------------------------------------------ ожидание чека в личке

    public void setPending(long userId, long pollId) {
        jdbc.update("""
                INSERT INTO lunch_pending_receipt(user_id, poll_id) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET poll_id = EXCLUDED.poll_id, created_at = now()
                """, userId, pollId);
    }

    public Long findPendingPoll(long userId) {
        List<Long> r = jdbc.queryForList(
                "SELECT poll_id FROM lunch_pending_receipt WHERE user_id = ?", Long.class, userId);
        return r.isEmpty() ? null : r.get(0);
    }

    public void clearPending(long userId) {
        jdbc.update("DELETE FROM lunch_pending_receipt WHERE user_id = ?", userId);
    }
}
