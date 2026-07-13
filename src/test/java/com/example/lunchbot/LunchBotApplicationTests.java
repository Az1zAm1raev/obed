package com.example.lunchbot;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Требует запущенный Postgres и TELEGRAM_BOT_TOKEN")
class LunchBotApplicationTests {

    @Test
    void contextLoads() {
    }
}
