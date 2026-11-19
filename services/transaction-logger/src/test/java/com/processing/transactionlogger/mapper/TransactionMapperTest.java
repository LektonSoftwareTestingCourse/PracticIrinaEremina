package com.processing.transactionlogger.mapper;

import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.transactionlogger.model.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionMapperTest {

    private final TransactionMapper mapper = new TransactionMapper();

    @Test
    void shouldMatchTimestampAfterPostgresMicrosecondConversion() {
        Instant requestTime = Instant.parse("2026-11-19T10:15:30.123456789Z");
        TransactionRequest request = request(requestTime);
        Transaction stored = mapper.toEntity(request);
        stored.setTransmissionDateTime(Instant.parse("2026-11-19T10:15:30.123457Z"));

        assertTrue(mapper.matches(stored, request));
    }

    private static TransactionRequest request(Instant transmissionDateTime) {
        return new TransactionRequest(
                UUID.randomUUID(),
                "0100",
                "123456",
                "123456789012",
                "4000001234567890",
                "000000",
                new BigDecimal("1000"),
                "643",
                "TERM0001",
                "POS",
                "MERCH0000000001",
                "5411",
                "ACQ001",
                "ISS001",
                new BigDecimal("15"),
                TransactionStatus.APPROVED,
                null,
                "ABC123",
                5,
                transmissionDateTime,
                transmissionDateTime
        );
    }
}
