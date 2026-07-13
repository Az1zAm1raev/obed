package com.example.lunchbot.all;

import java.util.List;

public record LunchPollRequest(
        String title,
        List<String> options
) {
}
