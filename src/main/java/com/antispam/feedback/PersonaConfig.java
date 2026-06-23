package com.antispam.feedback;

/**
 * A persona's open-ended configuration, persisted as the {@code user_personas.config_json}
 * JSONB column (story 07.01). Today it carries only the malicious flag — the PRD §Data Model
 * places that flag in {@code config_json}, not in a typed column — but being a JSONB-backed
 * record means a later persona knob (an attack intensity for the report/rescue bombers, 07.04,
 * say) is a field added here, not a schema migration.
 *
 * @param malicious whether this persona attacks the feedback channel (report/rescue bombing)
 *                  rather than acting in good faith; the weighting/corroboration gate (07.03)
 *                  and the sensitivity sweep (07.04) branch on it
 */
public record PersonaConfig(boolean malicious) {
}
