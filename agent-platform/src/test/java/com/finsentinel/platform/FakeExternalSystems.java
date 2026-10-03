package com.finsentinel.platform;

import com.finsentinel.platform.systems.AccountDetails;
import com.finsentinel.platform.systems.CustomerProfile;
import com.finsentinel.platform.systems.ExternalSystems;
import com.finsentinel.platform.systems.ScreeningResult;
import com.finsentinel.platform.systems.SystemUnavailableException;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Replaces the MCP-backed systems in tests. Every id gets a plain answer unless a test sets a story for it or takes a
 * system down. Records each request, so tests can check what the tools asked for. reset() restores the defaults.
 */
@TestComponent
@Primary
public class FakeExternalSystems implements ExternalSystems {

    public static final String BANKING = "banking";
    public static final String COMPLIANCE = "compliance";

    private final Map<String, CustomerProfile> profiles = new ConcurrentHashMap<>();
    private final Map<String, AccountDetails> accounts = new ConcurrentHashMap<>();
    private final Map<String, ScreeningResult> screenings = new ConcurrentHashMap<>();
    private final Set<String> down = ConcurrentHashMap.newKeySet();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @Override
    public CustomerProfile customerProfile(String tenantId, String customerId) {
        record(BANKING, "customerProfile " + tenantId + "/" + customerId);
        return profiles.getOrDefault(key(tenantId, customerId), new CustomerProfile(customerId, "MASS",
                LocalDate.parse("2019-03-14"), "LOW", "Salaried employee", "SG", List.of(), List.of()));
    }

    @Override
    public AccountDetails accountDetails(String tenantId, String accountId) {
        record(BANKING, "accountDetails " + tenantId + "/" + accountId);
        return accounts.getOrDefault(key(tenantId, accountId),
                new AccountDetails(accountId, "ACTIVE", "Everyday Savings", LocalDate.parse("2019-03-14"), List.of()));
    }

    @Override
    public ScreeningResult screenCounterparty(String tenantId, String counterpartyId) {
        record(COMPLIANCE, "screenCounterparty " + tenantId + "/" + counterpartyId);
        return screenings.getOrDefault(key(tenantId, counterpartyId), new ScreeningResult(counterpartyId, List.of()));
    }

    public void profile(String tenantId, CustomerProfile profile) {
        profiles.put(key(tenantId, profile.customerId()), profile);
    }

    public void account(String tenantId, AccountDetails account) {
        accounts.put(key(tenantId, account.accountId()), account);
    }

    public void screening(String tenantId, ScreeningResult result) {
        screenings.put(key(tenantId, result.counterpartyId()), result);
    }

    public void down(String system) {
        down.add(system);
    }

    public List<String> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        profiles.clear();
        accounts.clear();
        screenings.clear();
        down.clear();
        requests.clear();
    }

    private void record(String system, String request) {
        requests.add(request);
        if (down.contains(system)) {
            throw new SystemUnavailableException(system, "connection refused (test)");
        }
    }

    private static String key(String tenantId, String id) {
        return tenantId + "/" + id;
    }
}
