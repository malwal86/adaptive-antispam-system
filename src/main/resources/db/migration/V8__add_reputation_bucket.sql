-- Soft auth-gated accrual (story 03.03, PRD §Subsystem 3). Each reputation signal now
-- records which bucket it accrued into, decided by the email's DMARC alignment, so a
-- spoofer cannot inherit a warmed-up domain's trust. The bucket is part of the
-- append-only audit trail (AC 5: which bucket, what weight) and is summed separately at
-- read time, where the unauthenticated bucket's trust is capped at neutral
-- (com.antispam.reputation.GatedReputation).
--
-- 'AUTHENTICATED'   — signal from DMARC-aligned mail; accrues at full weight.
-- 'UNAUTHENTICATED' — signal from mail that did not prove alignment; capped at neutral.

-- Backfill existing rows as AUTHENTICATED: events written before auth-gating were
-- full-weight, manually/seed-injected signals with no separate untrusted bucket, so
-- this preserves their prior meaning. Then drop the default — every new insert sets the
-- bucket explicitly via the application, so a forgotten value should fail loudly rather
-- than silently land in the trusted bucket.
alter table reputation_events
    add column bucket text not null default 'AUTHENTICATED';

alter table reputation_events
    alter column bucket drop default;

-- Guard the column to the known enum (com.antispam.reputation.ReputationBucket): a typo
-- must not create a third, silently-ignored bucket that the read query would drop.
alter table reputation_events
    add constraint reputation_events_bucket_valid
    check (bucket in ('AUTHENTICATED', 'UNAUTHENTICATED'));
