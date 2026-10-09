package com.finsentinel.platform.security;

import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.TestIssuer;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@PlatformTest
class ServiceTokenTest {

    @Autowired
    ServiceToken serviceToken;

    @Test
    void clientCredentialsTokenForTheSystemsIsFetchedOnceAndReused() throws Exception {
        int before = TestIssuer.tokensIssued();

        String first = serviceToken.bearer();
        String second = serviceToken.bearer();

        assertThat(second).isEqualTo(first);
        assertThat(TestIssuer.tokensIssued() - before).isLessThanOrEqualTo(1);
        assertThat(SignedJWT.parse(first).getJWTClaimsSet().getAudience()).contains("mcp-banking", "mcp-compliance");
    }
}
