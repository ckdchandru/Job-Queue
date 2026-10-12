package com.jq.handler;

// goes straight to dead
public class NonRetryableException extends RuntimeException {

    public NonRetryableException(String message) {
        super(message);
    }
}
