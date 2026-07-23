package com.example.lunchbot.dish;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class DishRepository {

    private final JdbcTemplate jdbc;

    private static Dish map(ResultSet rs, int n) throws SQLException {
        return new Dish(rs.getLong("id"), rs.getString("name"), rs.getString("photo_file_id"));
    }

    // ------------------------------------------------------------ поиск

    public Optional<Dish> findByAlias(String norm) {
        return jdbc.query("""
                SELECT d.id, d.name, d.photo_file_id
                FROM dish_alias a JOIN dish d ON d.id = a.dish_id
                WHERE a.alias = ? AND d.active
                """, DishRepository::map, norm).stream().findFirst();
    }

    /** Ближайшее активное блюдо в пределах порога. Сравниваем и с алиасами. */
    public Optional<Dish> findNearest(String norm) {
        return jdbc.query("""
                SELECT d.id, d.name, d.photo_file_id, min(levenshtein(a.alias, ?)) AS dist
                FROM dish_alias a JOIN dish d ON d.id = a.dish_id
                WHERE d.active AND abs(length(a.alias) - length(?)) <= ?
                GROUP BY d.id, d.name, d.photo_file_id
                HAVING min(levenshtein(a.alias, ?)) <= ?
                ORDER BY dist
                LIMIT 1
                """, DishRepository::map,
                        norm, norm, DishMatcher.MAX_DISTANCE, norm, DishMatcher.MAX_DISTANCE)
                .stream().findFirst();
    }

    public Optional<Dish> findById(long id) {
        return jdbc.query("SELECT id, name, photo_file_id FROM dish WHERE id = ?",
                DishRepository::map, id).stream().findFirst();
    }

    /** Сначала блюда без фото — они единственные требуют действия. */
    public List<Dish> catalog() {
        return jdbc.query("""
                SELECT id, name, photo_file_id FROM dish WHERE active
                ORDER BY (photo_file_id IS NOT NULL), name
                """, DishRepository::map);
    }

    public List<String> aliasesOf(long dishId) {
        return jdbc.queryForList("SELECT alias FROM dish_alias WHERE dish_id = ? ORDER BY alias",
                String.class, dishId);
    }

    /** Блюда текущего опроса, у которых есть фото. */
    /** Все названия блюд опроса — включая те, у которых нет фото и нет карточки в каталоге. */
    public List<String> allPollDishNames(long pollId) {
        return jdbc.queryForList("""
                SELECT text FROM lunch_poll_option
                WHERE poll_id = ?
                ORDER BY number
                """, String.class, pollId);
    }

    public List<Dish> dishesWithPhoto(long pollId) {
        return jdbc.query("""
                SELECT DISTINCT d.id, d.name, d.photo_file_id
                FROM lunch_poll_option o JOIN dish d ON d.id = o.dish_id
                WHERE o.poll_id = ? AND d.photo_file_id IS NOT NULL
                ORDER BY d.name
                """, DishRepository::map, pollId);
    }

    // ------------------------------------------------------------ запись

    public long create(String name, long adminId) {
        Long id = jdbc.queryForObject(
                "INSERT INTO dish(name) VALUES (?) RETURNING id", Long.class, name.strip());
        addAlias(DishMatcher.normalize(name), id, adminId);
        return id;
    }

    public void addAlias(String norm, long dishId, long adminId) {
        jdbc.update("""
                INSERT INTO dish_alias(alias, dish_id, added_by) VALUES (?, ?, ?)
                ON CONFLICT (alias) DO NOTHING
                """, norm, dishId, adminId);
    }

    public void setPhoto(long dishId, String fileId, String uniqueId) {
        jdbc.update("UPDATE dish SET photo_file_id = ?, photo_unique_id = ? WHERE id = ?",
                fileId, uniqueId, dishId);
    }

    /** Мягкое удаление: на блюдо ссылаются опции прошлых опросов. */
    public void deactivate(long dishId) {
        jdbc.update("UPDATE dish SET active = FALSE WHERE id = ?", dishId);
    }

    /** Дубль: переносим алиасы и опции на основное блюдо, источник гасим. */
    public void merge(long fromId, long toId) {
        jdbc.update("UPDATE dish_alias SET dish_id = ? WHERE dish_id = ?", toId, fromId);
        jdbc.update("UPDATE lunch_poll_option SET dish_id = ? WHERE dish_id = ?", toId, fromId);
        deactivate(fromId);
    }

    public void setOptionDish(long optionId, long dishId) {
        jdbc.update("UPDATE lunch_poll_option SET dish_id = ? WHERE id = ?", dishId, optionId);
    }

    // ---------------------------------------------------------- вопросы

    public long addQuestion(long pollId, long optionId, String raw, String norm, long suggestId) {
        return jdbc.queryForObject("""
                INSERT INTO dish_question(poll_id, option_id, raw_text, norm_text, suggest_id)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, pollId, optionId, raw, norm, suggestId);
    }

    public void resolveQuestion(long id) {
        jdbc.update("UPDATE dish_question SET resolved = TRUE WHERE id = ?", id);
    }

    public record Question(long id, long optionId, String rawText, String normText, long suggestId) {}

    public Optional<Question> findQuestion(long id) {
        return jdbc.query("""
                SELECT id, option_id, raw_text, norm_text, suggest_id
                FROM dish_question WHERE id = ? AND NOT resolved
                """, (rs, n) -> new Question(rs.getLong("id"), rs.getLong("option_id"),
                        rs.getString("raw_text"), rs.getString("norm_text"), rs.getLong("suggest_id")),
                id).stream().findFirst();
    }

    // ------------------------------------------------- состояние админа

    public void setState(long userId, long chatId, String state, Long payload) {
        jdbc.update("""
                INSERT INTO admin_state(user_id, chat_id, state, payload) VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                   SET state = EXCLUDED.state, payload = EXCLUDED.payload, created_at = now()
                """, userId, chatId, state, payload);
    }

    public record State(String state, Long payload) {}

    /** Состояние живёт 10 минут: иначе забытый диалог будет глотать сообщения в группе. */
    public Optional<State> findState(long userId) {
        return jdbc.query("""
                SELECT state, payload FROM admin_state
                WHERE user_id = ? AND created_at > now() - interval '10 minutes'
                """,
                (rs, n) -> new State(rs.getString("state"), (Long) rs.getObject("payload")),
                userId).stream().findFirst();
    }

    public void clearState(long userId) {
        jdbc.update("DELETE FROM admin_state WHERE user_id = ?", userId);
    }
}