package com.antispam.campaign.web;

import com.antispam.campaign.CampaignClusteringService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The offline campaign-clustering endpoints (story 06.03).
 *
 * <p>{@code POST /campaigns/cluster} runs the job — cluster every stored embedding
 * at the current version into campaigns and replace that version's
 * {@code campaign_clusters} — and returns the run summary. It is deliberately an
 * out-of-band trigger, not part of decisioning: the job reads embeddings and writes
 * clusters only, so it never affects an in-flight verdict.
 *
 * <p>{@code GET /campaigns/clusters/email/{emailId}} returns the campaign an email
 * belongs to, or 404 if it has not been clustered (no run yet, or no embedding).
 */
@RestController
@RequestMapping("/campaigns")
public class CampaignClusteringController {

    private final CampaignClusteringService service;

    @Autowired
    public CampaignClusteringController(CampaignClusteringService service) {
        this.service = service;
    }

    @PostMapping(path = "/cluster", produces = MediaType.APPLICATION_JSON_VALUE)
    public ClusteringRunResponse cluster() {
        return ClusteringRunResponse.from(service.cluster());
    }

    @GetMapping(path = "/clusters/email/{emailId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClusterMembershipResponse> membership(
            @PathVariable("emailId") UUID emailId) {
        return service.membershipOf(emailId)
                .map(ClusterMembershipResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
