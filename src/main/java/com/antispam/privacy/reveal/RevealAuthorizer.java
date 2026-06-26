package com.antispam.privacy.reveal;

import com.antispam.config.RevealAccessProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Authorizes privileged, redaction-bypassing access to email PII (story 14.05):
 * {@code ?reveal=true}, {@code /emails/{id}/raw}, and erasure. A caller must present a
 * bearer token matching the configured shared secret; the call returns the actor to
 * record in the access audit, or throws.
 *
 * <p><b>Fail closed:</b> with no secret configured there is no acceptable credential, so
 * every privileged request is forbidden — a deployment that forgets to set the secret
 * cannot accidentally serve unmasked PII. The token comparison is constant-time so a
 * wrong guess leaks no timing signal.
 */
@Component
public class RevealAuthorizer {

    private static final String BEARER_PREFIX = "Bearer ";

    /** The actor recorded when an authorized caller names no specific principal. */
    private static final String DEFAULT_ACTOR = "operator";

    private final RevealAccessProperties properties;

    @Autowired
    public RevealAuthorizer(RevealAccessProperties properties) {
        this.properties = properties;
    }

    /**
     * Authorizes a privileged request and returns the actor to audit.
     *
     * @param authorizationHeader the {@code Authorization} header (expected {@code Bearer <secret>})
     * @param actorHeader         optional caller-supplied principal name for the audit; may be null
     * @return the actor to record (the supplied name, or {@code operator} when none was given)
     * @throws RevealUnauthorizedException if no bearer credentials were presented (401)
     * @throws RevealForbiddenException    if the secret is unconfigured or the token is wrong (403)
     */
    public String authorize(String authorizationHeader, String actorHeader) {
        if (!properties.isConfigured()) {
            throw new RevealForbiddenException("reveal access is not configured");
        }
        String token = bearerToken(authorizationHeader);
        if (token == null) {
            throw new RevealUnauthorizedException("missing bearer credentials");
        }
        if (!constantTimeEquals(token, properties.secret())) {
            throw new RevealForbiddenException("invalid reveal credentials");
        }
        return actorHeader == null || actorHeader.isBlank() ? DEFAULT_ACTOR : actorHeader.trim();
    }

    private static String bearerToken(String header) {
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        if (!trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = trimmed.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
