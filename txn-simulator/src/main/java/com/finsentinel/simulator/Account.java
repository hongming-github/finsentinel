package com.finsentinel.simulator;

import java.math.BigDecimal;
import java.util.List;

/**
 * A synthetic account.
 *
 * @param typicalAmount    median of its normal spending
 * @param regularMerchants merchants the customer keeps coming back to
 * @param regularPayees    people or businesses the customer regularly transfers to
 */
record Account(String tenantId, String accountId, String customerId, String homeCountry, BigDecimal typicalAmount,
               List<String> regularMerchants, List<String> regularPayees) {
}
