package com.antispam.privacy.reveal;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The single entry point the API uses to gate and record privileged access to email
 * PII (story 14.05). It bundles the two halves of the control — authorize the request,
 * then audit the access — so the controller can keep its happy path clean and so the
 * "reveal requires authz, and every reveal is audited" invariant lives in one place.
 *
 * <p>Authorization and auditing are separate calls on purpose: a caller authorizes
 * first (rejecting 401/403 before any data is touched) and records the access only once
 * it has confirmed there is something to serve — so a probe for a non-existent id is
 * rejected or 404'd without polluting the audit.
 */
@Service
public class RevealAccessService {

    private final RevealAuthorizer authorizer;
    private final RevealAccessAuditRepository audit;

    @Autowired
    public RevealAccessService(RevealAuthorizer authorizer, RevealAccessAuditRepository audit) {
        this.authorizer = authorizer;
        this.audit = audit;
    }

    /**
     * Authorizes a privileged request, returning the actor to attribute the access to.
     *
     * @throws RevealUnauthorizedException if no credentials were presented (401)
     * @throws RevealForbiddenException    if the secret is unconfigured or the token is wrong (403)
     */
    public String authorize(String authorizationHeader, String actorHeader) {
        return authorizer.authorize(authorizationHeader, actorHeader);
    }

    /** Records one authorized, served access in the audit log. */
    public void record(UUID emailId, String actor, RevealAccessType type) {
        audit.record(emailId, actor, type);
    }
}
