-- Story 09.02: designate (at most) one policy as the SHADOW policy — the candidate regime scored
-- logged-only against live traffic alongside the active enforcing one (PRD §Subsystem 8). Mirrors
-- the `active` flag: a selector, not a property of the regime, so it lives on the row rather than
-- in the Policy value object. A partial unique index allows at most one shadow at a time, exactly
-- as the active flag is constrained, so "the shadow policy" is always unambiguous.

alter table policies
    add column shadow boolean not null default false;

create unique index policies_one_shadow on policies (shadow) where shadow;
