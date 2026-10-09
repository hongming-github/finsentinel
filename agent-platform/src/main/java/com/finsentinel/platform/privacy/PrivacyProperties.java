package com.finsentinel.platform.privacy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code finsentinel.privacy}.
 *
 * @param pseudonymKey secret for the pseudonyms that replace personal data in model prompts. Without it a pseudonym
 *                     cannot be traced back to the value; with it, a value can be checked against one. Change it and
 *                     the same value gets a different pseudonym (prompts are not stored, so nothing breaks)
 */
@ConfigurationProperties("finsentinel.privacy")
public record PrivacyProperties(String pseudonymKey) {

    public PrivacyProperties {
        if (pseudonymKey == null || pseudonymKey.length() < 16) {
            throw new IllegalArgumentException("finsentinel.privacy.pseudonym-key needs at least 16 characters");
        }
    }
}
