package com.finsentinel.platform.transaction;

import com.finsentinel.common.Topics;
import com.finsentinel.common.Transaction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Copies the transaction stream into Postgres so agents can query account history.
 * Batched for throughput; unparseable records are skipped here because the stream job already
 * routes them to the transactions DLQ.
 */
@Component
class TransactionStoreListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionStoreListener.class);

    private final JsonMapper json;
    private final TransactionRepository transactions;

    TransactionStoreListener(JsonMapper json, TransactionRepository transactions) {
        this.json = json;
        this.transactions = transactions;
    }

    @KafkaListener(topics = Topics.TRANSACTIONS, groupId = "agent-platform-txn-store", batch = "true")
    void onBatch(List<ConsumerRecord<String, String>> records) {
        List<Transaction> batch = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            try {
                Transaction txn = json.readValue(record.value(), Transaction.class);
                if (txn.txnId() != null && txn.tenantId() != null && txn.accountId() != null) {
                    batch.add(txn);
                }
            } catch (JacksonException e) {
                log.debug("Skipping unparseable transaction at {}-{}@{}", record.topic(), record.partition(), record.offset());
            }
        }
        if (!batch.isEmpty()) {
            transactions.insertAll(batch);
        }
    }
}
