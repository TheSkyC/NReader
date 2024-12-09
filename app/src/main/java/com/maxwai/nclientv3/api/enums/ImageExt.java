package com.maxwai.nclientv3.api.enums;

public enum ImageExt {
    JPG("jpg"), PNG("png"), GIF("gif"), WEBP("webp");

    private final String name;

    ImageExt(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
