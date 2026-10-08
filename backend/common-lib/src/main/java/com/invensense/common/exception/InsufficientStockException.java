package com.invensense.common.exception;

public class InsufficientStockException extends RuntimeException {
    private final String sku;
    private final int available;

    public InsufficientStockException(String sku, int available) {
        super("Only " + available + " units available for " + sku);
        this.sku = sku;
        this.available = available;
    }

    public String getSku() { return sku; }
    public int getAvailable() { return available; }
}
