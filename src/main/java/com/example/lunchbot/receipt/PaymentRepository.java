package com.example.lunchbot.receipt;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Денежный баланс. Каждый одобренный чек добавляет свою сумму.
 * Блюдо списывает цену человека. Баланс = сумма чеков − число блюд × цена.
 */
@Repository
@RequiredArgsConstructor
public class PaymentRepository {

    private final JdbcTemplate jdbc;

    // ------------------------------------------------ сохранение чека

    /** @return id чека, либо null если tx_id уже использован (дубль). */
    public Long saveReceipt(long pollId, long userId, String status, String fileId, String txId, int amount) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO lunch_receipt(poll_id, user_id, status, file_id, tx_id, amount)
                    VALUES (?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """, Long.class, pollId, userId, status, fileId, txId, amount);
        } catch (DuplicateKeyException e) {
            return null;   // ux_receipt_tx: чек уже засчитан
        }
    }

    // ------------------------------------------------ баланс в деньгах

    /** Сумма всех одобренных чеков человека за опрос. */
    public int paidTotal(long pollId, long userId) {
        Integer sum = jdbc.queryForObject("""
                SELECT COALESCE(sum(amount), 0) FROM lunch_receipt
                WHERE poll_id = ? AND user_id = ? AND status = 'APPROVED'
                """, Integer.class, pollId, userId);
        return sum == null ? 0 : sum;
    }

    /** Все, кто оплатил в этом опросе: user_id -> сумма одобренных чеков. */
    public java.util.Map<Long, Integer> paidByUser(long pollId) {
        java.util.Map<Long, Integer> out = new java.util.HashMap<>();
        jdbc.query("""
                SELECT user_id, COALESCE(sum(amount),0) AS total
                FROM lunch_receipt
                WHERE poll_id = ? AND status = 'APPROVED'
                GROUP BY user_id
                """, rs -> {
            out.put(rs.getLong("user_id"), rs.getInt("total"));
        }, pollId);
        return out;
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

    /** Очистить ожидания по конкретному опросу — при его закрытии. */
    public void clearPendingByPoll(long pollId) {
        jdbc.update("DELETE FROM lunch_pending_receipt WHERE poll_id = ?", pollId);
    }
}