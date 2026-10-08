package com.invensense.common.exception;

public class LockBusyException extends RuntimeException {
    public LockBusyException(String message) {
        super(message);
    }
}
