-- Story 07.01: synthetic-user personas — the controllable population that drives the feedback
-- loop (PRD §Subsystem 7, §Data Model `user_personas`). Each persona carries behavioral biases
-- (how readily it clicks / reports, how much risk it tolerates); the truth-conditioned sampler
-- (07.02) turns (ground_truth, decision_shown, persona) into actions, and the sensitivity sweep
-- (07.04) varies the persona mix. Personas are defined entirely in config (antispam.personas) and
-- seeded here, so adding a benign or malicious type is a YAML entry, not a code change.

create table user_personas (
    -- Content-addressed id (UUIDv3 over the name, com.antispam.feedback.Persona.idForName), so
    -- re-seeding the same definition writes the same row rather than a duplicate, and a population
    -- spec naming a persona resolves to the same persona across runs and machines (reproducibility).
    id              uuid                primary key,

    -- The stable, unique persona name the population spec references.
    name            text                not null unique,

    -- Behavioral biases, each a probability in [0,1] (the CHECKs below are the DB-side guard; the
    -- domain Persona and the config boundary validate the same range). click_bias / report_bias are
    -- how readily the persona clicks a link or reports mail; risk_tolerance is its appetite for risky
    -- content. These are the knobs 07.02 samples actions from and 07.04 sweeps.
    click_bias      double precision    not null,
    report_bias     double precision    not null,
    risk_tolerance  double precision    not null,

    -- Open-ended config (com.antispam.feedback.PersonaConfig) as JSONB. The PRD places the malicious
    -- flag here rather than in a typed column; JSONB keeps later persona knobs (07.04) a serialization
    -- change, not a migration.
    config_json     jsonb               not null,

    created_at      timestamptz         not null default now(),

    constraint user_personas_click_bias_unit check (click_bias between 0 and 1),
    constraint user_personas_report_bias_unit check (report_bias between 0 and 1),
    constraint user_personas_risk_tolerance_unit check (risk_tolerance between 0 and 1)
);
