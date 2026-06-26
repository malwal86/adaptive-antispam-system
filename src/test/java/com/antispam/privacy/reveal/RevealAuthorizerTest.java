package com.antispam.privacy.reveal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antispam.config.RevealAccessProperties;
import org.junit.jupiter.api.Test;

/**
 * Server-side authorization for the redaction-bypassing accessors (story 14.05): a
 * matching bearer secret is required, a missing one is 401, a wrong one is 403, and
 * — fail-closed — an unconfigured secret denies everyone.
 */
class RevealAuthorizerTest {

    private final RevealAuthorizer authorizer =
            new RevealAuthorizer(new RevealAccessProperties("s3cret-token"));

    @Test
    void a_matching_bearer_token_is_authorized_and_yields_the_default_actor() {
        assertThat(authorizer.authorize("Bearer s3cret-token", null)).isEqualTo("operator");
    }

    @Test
    void a_named_actor_is_returned_for_the_audit_trail() {
        assertThat(authorizer.authorize("Bearer s3cret-token", "alice@ops")).isEqualTo("alice@ops");
    }

    @Test
    void a_missing_authorization_header_is_unauthorized() {
        assertThatThrownBy(() -> authorizer.authorize(null, null))
                .isInstanceOf(RevealUnauthorizedException.class);
    }

    @Test
    void a_non_bearer_authorization_header_is_unauthorized() {
        assertThatThrownBy(() -> authorizer.authorize("Basic abc123", null))
                .isInstanceOf(RevealUnauthorizedException.class);
    }

    @Test
    void a_wrong_token_is_forbidden() {
        assertThatThrownBy(() -> authorizer.authorize("Bearer wrong-token", null))
                .isInstanceOf(RevealForbiddenException.class);
    }

    @Test
    void an_unconfigured_secret_denies_everyone_fail_closed() {
        RevealAuthorizer unconfigured = new RevealAuthorizer(new RevealAccessProperties("  "));
        assertThatThrownBy(() -> unconfigured.authorize("Bearer anything", null))
                .isInstanceOf(RevealForbiddenException.class);
    }
}
