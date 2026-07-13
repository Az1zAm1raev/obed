package com.example.lunchbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LunchBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(LunchBotApplication.class, args);
    }

}
