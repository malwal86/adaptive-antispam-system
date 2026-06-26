package com.antispam.privacy.reveal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A privileged reveal/raw/erasure request was refused (story 14.05): the presented
 * credential was wrong, or reveal access is not configured at all (fail-closed). Maps
 * to 403 Forbidden — retrying with the same credential will not help. A request with no
 * credentials at all is the distinct {@link RevealUnauthorizedException} (401).
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class RevealForbiddenException extends RuntimeException {

    public RevealForbiddenException(String message) {
        super(message);
    }
}
