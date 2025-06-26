package com.iso.validation.core;

/**
 * Represents the result of a single field validation.
 */
public class FieldResult {
    private final FieldStatus status;
    private final String expected;
    private final String actual;

    public FieldResult(FieldStatus status, String expected, String actual) {
        this.status = status;
        this.expected = expected;
        this.actual = actual;
    }

    public FieldStatus getStatus() { 
        return status; 
    }

    public String getExpected() { 
        return expected; 
    }

    public String getActual() { 
        return actual; 
    }
} 