package com.finsentinel.platform.security;

import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * The platform's own access token for the systems it calls (mcp-banking, mcp-compliance): client credentials of
 * registration {@value #REGISTRATION}. Cached until shortly before it expires; usable from any thread, including the
 * analysis graph's pool threads, because it depends on no request.
 */
@Component
public class ServiceToken {

    static final String REGISTRATION = "platform-service";

    private final AuthorizedClientServiceOAuth2AuthorizedClientManager manager;

    ServiceToken(ClientRegistrationRepository registrations, OAuth2AuthorizedClientService clients) {
        this.manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        this.manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
    }

    /** @throws RuntimeException when the identity provider cannot issue one; callers treat the system as unavailable */
    public String bearer() {
        OAuth2AuthorizedClient client = manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION)
                .principal("finsentinel-platform").build());
        if (client == null) {
            throw new IllegalStateException("no access token for " + REGISTRATION);
        }
        return client.getAccessToken().getTokenValue();
    }
}
