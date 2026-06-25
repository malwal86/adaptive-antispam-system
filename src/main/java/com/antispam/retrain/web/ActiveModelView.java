package com.antispam.retrain.web;

import com.antispam.retrain.ModelVersionRecord;
import java.util.List;

/**
 * The runtime view of what the serving path is currently using (story 10.04 AC 5): the served model
 * version, the active policy it comes from, the model versions currently loaded into the registry, and
 * the most recent promotion. This is what makes "the active model is observable at runtime" concrete —
 * the same way the gate endpoint exposes the verdict, this exposes what won.
 *
 * @param servedModelVersion  the model the next decision will score with
 * @param activePolicyVersion the active policy that selects it, or null if none is active
 * @param loadedVersions      the model versions with a live session in the registry (sorted)
 * @param latestPromotion     the most recently promoted model_version, or null if none has been
 */
public record ActiveModelView(
        String servedModelVersion,
        String activePolicyVersion,
        List<String> loadedVersions,
        ModelVersionRecord latestPromotion) {
}
