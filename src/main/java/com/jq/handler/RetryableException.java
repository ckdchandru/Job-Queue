package com.jq.handler;

public class RetryableException extends RuntimeException {

    public RetryableException(String message) {
        super(message);
    }
}
