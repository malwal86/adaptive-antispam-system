package com.antispam.privacy.reveal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * No credentials were presented for a privileged reveal/raw/erasure request (story
 * 14.05): there was no usable bearer token at all. Maps to 401 Unauthorized — the
 * caller may retry with credentials. A present-but-wrong token is the distinct
 * {@link RevealForbiddenException} (403).
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class RevealUnauthorizedException extends RuntimeException {

    public RevealUnauthorizedException(String message) {
        super(message);
    }
}
