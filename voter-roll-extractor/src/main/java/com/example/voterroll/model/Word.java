package com.example.voterroll.model;

/**
 * One word on a page with its bounding box. Coordinates are top-down
 * (y grows downwards) in whatever unit the source uses (PDF points or image pixels).
 */
public record Word(String text, float x, float y, float width, float height) {

    public float right() {
        return x + width;
    }

    public float centerX() {
        return x + width / 2f;
    }

    public float centerY() {
        return y + height / 2f;
    }
}
