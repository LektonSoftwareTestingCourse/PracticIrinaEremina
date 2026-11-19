package com.processing.e2e.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestDataFactory {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(100000);

    private TestDataFactory() {
    }

    public static String uniqueStan() {
        return String.format("%06d", SEQUENCE.updateAndGet(value -> value >= 999998 ? 100000 : value + 1));
    }

    public static String uniqueRrn() {
        long value = (UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE) % 1_000_000_000_000L;
        return String.format("%012d", value);
    }

    private static String uniqueLetters() {
        return UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase()
                .replaceAll("[0-9]", "A");
    }

    public static Map<String, Object> cardRequest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bin", "400000");
        body.put("cardholderName", "API TEST " + uniqueLetters());
        body.put("currencyCode", "643");
        body.put("dailyLimit", new BigDecimal("100000"));
        body.put("monthlyLimit", new BigDecimal("1000000"));
        body.put("initialBalance", new BigDecimal("500000"));
        return body;
    }

    public static Map<String, Object> authorizationRequest(String pan, BigDecimal amount, String stan) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mti", "0100");
        body.put("stan", stan);
        body.put("pan", pan);
        body.put("processingCode", "000000");
        body.put("amount", amount);
        body.put("currencyCode", "643");
        body.put("transmissionDateTime", Instant.now().toString());
        body.put("terminalId", "TERM0001");
        body.put("terminalType", "POS");
        body.put("merchantId", "MERCH0000000001");
        body.put("mcc", "5411");
        body.put("acquirerId", "ACQ001");
        body.put("issuerId", "ISS001");
        return body;
    }

    public static Map<String, Object> loggerRequest(UUID id, String stan) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id.toString());
        body.put("mti", "0100");
        body.put("stan", stan);
        body.put("rrn", uniqueRrn());
        body.put("pan", "4000001234567890");
        body.put("processingCode", "000000");
        body.put("amount", new BigDecimal("1000"));
        body.put("currencyCode", "643");
        body.put("terminalId", "TERM0001");
        body.put("terminalType", "POS");
        body.put("merchantId", "MERCH0000000001");
        body.put("mcc", "5411");
        body.put("acquirerId", "ACQ001");
        body.put("issuerId", "ISS001");
        body.put("acquiringFee", new BigDecimal("15"));
        body.put("status", "APPROVED");
        body.put("declineReason", null);
        body.put("authCode", "ABC123");
        body.put("processingTimeMs", 5);
        body.put("transmissionDateTime", Instant.now().toString());
        body.put("createdAt", Instant.now().toString());
        return body;
    }
}
