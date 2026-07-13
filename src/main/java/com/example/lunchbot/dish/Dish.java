package com.example.lunchbot.dish;

public record Dish(long id, String name, String photoFileId) {
    public boolean hasPhoto() {
        return photoFileId != null && !photoFileId.isBlank();
    }
}
