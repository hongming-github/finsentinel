package com.finsentinel.simulator;

import java.math.BigDecimal;

/** A synthetic account; {@code typicalAmount} is the median of its normal spending. */
record Account(String tenantId, String accountId, String customerId, String homeCountry, BigDecimal typicalAmount) {
}
