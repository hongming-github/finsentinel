package com.finsentinel.platform.transaction;

import com.finsentinel.common.Topics;
import com.finsentinel.common.Transaction;
import com.finsentinel.contracts.AvroSerde;
import com.finsentinel.contracts.Contracts;
import com.finsentinel.contracts.avro.TransactionEvent;
import com.finsentinel.platform.security.Tenancy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Copies the transaction stream (Avro, ADR 0018) into Postgres so agents can query account history.
 * Batched for throughput; unreadable records are skipped here because the stream job already
 * routes them to the transactions DLQ. Any other failure (registry, database) fails the batch, which is retried until
 * it succeeds ({@link com.finsentinel.platform.alert.KafkaErrorHandling}).
 */
@Component
class TransactionStoreListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionStoreListener.class);

    private final AvroSerde<TransactionEvent> serde;
    private final TransactionRepository transactions;

    TransactionStoreListener(AvroSerde<TransactionEvent> serde, TransactionRepository transactions) {
        this.serde = serde;
        this.transactions = transactions;
    }

    @KafkaListener(topics = Topics.TRANSACTIONS, groupId = "agent-platform-txn-store", batch = "true")
    void onBatch(List<ConsumerRecord<String, byte[]>> records) {
        List<Transaction> batch = new ArrayList<>(records.size());
        for (ConsumerRecord<String, byte[]> record : records) {
            try {
                batch.add(Contracts.fromAvro(serde.deserialize(record.value())));
            } catch (AvroSerde.InvalidMessageException e) {
                log.debug("Skipping unparseable transaction at {}-{}@{}", record.topic(), record.partition(), record.offset());
            }
        }
        // A batch mixes banks; each bank's rows are written in its own tenant scope.
        batch.stream().collect(Collectors.groupingBy(Transaction::tenantId))
                .forEach((tenant, txns) -> Tenancy.run(tenant, () -> transactions.insertAll(txns)));
    }
}
