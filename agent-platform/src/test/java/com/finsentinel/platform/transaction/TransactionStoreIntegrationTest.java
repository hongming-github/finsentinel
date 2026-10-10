package com.finsentinel.platform.transaction;

import com.finsentinel.common.Channel;
import com.finsentinel.common.Topics;
import com.finsentinel.common.Transaction;
import com.finsentinel.contracts.AvroSerde;
import com.finsentinel.contracts.Contracts;
import com.finsentinel.contracts.avro.TransactionEvent;
import com.finsentinel.platform.PlatformTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.finsentinel.platform.TestData.txn;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@PlatformTest
class TransactionStoreIntegrationTest {

    @Autowired
    KafkaTemplate<String, byte[]> kafka;
    @Autowired
    AvroSerde<TransactionEvent> serde;
    @Autowired
    TransactionRepository repository;

    @Test
    void storesTransactionsOnceAndSkipsGarbage() {
        String tenant = uniqueTenant();
        Transaction t1 = txn(tenant, "acc-1", "12.50", Channel.CARD_PRESENT, "SG", Instant.now());
        Transaction t2 = txn(tenant, "acc-1", "99.00", Channel.TRANSFER, "SG", Instant.now());

        kafka.send(Topics.TRANSACTIONS, t1.accountKey(), avro(t1));
        kafka.send(Topics.TRANSACTIONS, t1.accountKey(), "{garbage".getBytes(StandardCharsets.UTF_8));
        kafka.send(Topics.TRANSACTIONS, t1.accountKey(), avro(t1));
        kafka.send(Topics.TRANSACTIONS, t2.accountKey(), avro(t2));

        await().pollInSameThread().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(repository.findRecent(tenant, "acc-1", Instant.now().minusSeconds(3600), 100))
                        .containsExactlyInAnyOrder(t1, t2));
    }

    @Test
    void profileLeavesOutExcludedAndLaterTransactions() {
        String tenant = uniqueTenant();
        Instant alertTime = Instant.now();
        Transaction normal = txn(tenant, "acc-a", "50", Channel.CARD_PRESENT, "SG", alertTime.minusSeconds(3600));
        Transaction flagged = txn(tenant, "acc-a", "900", Channel.CARD_PRESENT, "GB", alertTime.minusSeconds(60));
        Transaction after = txn(tenant, "acc-a", "70", Channel.CARD_PRESENT, "SG", alertTime.plusSeconds(60));
        repository.insertAll(List.of(normal, flagged, after));

        AccountProfile profile = repository.profile(tenant, "acc-a", alertTime.minusSeconds(86_400), alertTime,
                List.of(flagged.txnId()));

        assertThat(profile.amounts().transactionCount()).isEqualTo(1);
        assertThat(profile.cardPresentCountryCounts()).containsOnlyKeys("SG");
    }

    @Test
    void profileSummarisesOnlyTheRequestedAccount() {
        String tenant = uniqueTenant();
        Instant now = Instant.now();
        repository.insertAll(List.of(
                txn(tenant, "acc-a", "10", Channel.CARD_PRESENT, "SG", now.minusSeconds(300)),
                txn(tenant, "acc-a", "30", Channel.CARD_PRESENT, "MY", now.minusSeconds(200)),
                txn(tenant, "acc-a", "20", Channel.CARD_NOT_PRESENT, "US", now.minusSeconds(100)),
                txn(tenant, "acc-b", "5000", Channel.TRANSFER, "SG", now.minusSeconds(100))));

        AccountProfile profile = repository.profile(tenant, "acc-a", now.minusSeconds(3600), now, List.of());

        assertThat(profile.amounts().transactionCount()).isEqualTo(3);
        assertThat(profile.amounts().max()).isEqualByComparingTo("30");
        assertThat(profile.amounts().median()).isEqualByComparingTo("20");
        assertThat(profile.channelCounts()).containsEntry("CARD_PRESENT", 2L).containsEntry("CARD_NOT_PRESENT", 1L);
        assertThat(profile.cardPresentCountryCounts()).containsOnlyKeys("SG", "MY");
    }

    private byte[] avro(Transaction txn) {
        return serde.serialize(Contracts.toAvro(txn));
    }
}
