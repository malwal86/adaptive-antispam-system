package com.antispam.eval.web;

import com.antispam.seed.GroundTruthLabel;
import java.util.UUID;

/**
 * {@code POST /eval/fresh} body: one reported attack to append to the rolling fresh challenge set
 * (story 11.02). The label is the email's high-confidence class — the fresh set, like the golden set,
 * is judged only against trusted labels, never simulator feedback (story 11.03).
 *
 * @param emailId the reported email
 * @param label   its high-confidence class (ham/spam/phish)
 * @param source  where the report came from (audit)
 */
public record AddFreshChallengeRequest(UUID emailId, GroundTruthLabel label, String source) {
}
