package com.finsentinel.batch;

import com.finsentinel.common.Channel;
import com.finsentinel.common.Transaction;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/** Writes landing files the way the stream job does (Jackson 3 JSON Lines under dt=UTC date). */
final class Landing {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Path root;

    Landing(Path lake) {
        this.root = lake.resolve("landing/transactions");
    }

    Transaction txn(String tenant, String account, String amount, Instant at) {
        return new Transaction(UUID.randomUUID().toString(), tenant, account, "cus-" + account, new BigDecimal(amount),
                "SGD", "m-1", "5411", Channel.CARD_PRESENT, "SG", at);
    }

    Landing write(Transaction txn) {
        return line(LocalDate.ofInstant(txn.eventTime(), ZoneOffset.UTC), JSON.writeValueAsString(txn));
    }

    Landing line(LocalDate day, String line) {
        try {
            Path dir = Files.createDirectories(root.resolve("dt=" + day));
            Files.writeString(dir.resolve("txn-0.jsonl"), line + "\n", StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }
}
