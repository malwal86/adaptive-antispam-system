package com.antispam.decision;

import com.antispam.ingest.Email;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Phase-0 stand-in for the content model. The real classifier — calibrated ONNX
 * scores fused with sender reputation — arrives in Epic 04; until then, mail that
 * no hard rule overrides is provisionally allowed and tagged
 * {@link RouteUsed#MODEL}. This is a documented placeholder, not a real verdict:
 * Epic 04 <em>replaces</em> this bean rather than extending it.
 *
 * <p>It exists so the {@link DecisionService} fast path is complete and the
 * "model skipped on a hard-rule hit" guarantee is verifiable today.
 */
@Component
public class PlaceholderContentClassifier implements ContentClassifier {

    @Override
    public DecisionOutcome classify(Email email) {
        return new DecisionOutcome(Decision.ALLOW, List.of(), RouteUsed.MODEL, 0L);
    }
}
