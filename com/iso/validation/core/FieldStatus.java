package com.iso.validation.core;

/**
 * Represents the status of a field validation.
 */
public enum FieldStatus {
    PASSED("PASS"),
    FAILED("FAIL"),
    SKIPPED("SKIP"),
    PENDING("PENDING");  // Added for fields waiting for their pair

    private final String display;

    FieldStatus(String display) {
        this.display = display;
    }

    @Override
    public String toString() {
        return display;
    }
} 