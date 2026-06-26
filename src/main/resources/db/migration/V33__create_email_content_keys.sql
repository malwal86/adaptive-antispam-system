-- Story 14.02: the per-record key store that makes crypto-shredding possible.
--
-- The canonical `emails` row is immutable (V1) and its `raw_content` is now stored
-- as ciphertext (envelope encryption). The data key that decrypts a given body is
-- generated per record, wrapped under a master key, and persisted HERE — in a
-- separate, MUTABLE table — never in the immutable row.
--
-- That separation is the whole design: honoring a right-to-erasure request
-- (GDPR Art. 17) destroys the wrapped data key in this table (sets it null), which
-- leaves the immutable ciphertext permanently unrecoverable WITHOUT updating or
-- deleting the `emails` row. Immutability and erasure coexist. Destroying a key to
-- de-identify data is a recognized technique (ISO/IEC 20889).
--
-- A row exists here only for emails written while encryption was configured; an
-- email stored as plaintext (encryption disabled) has no row here, and the read
-- path treats "no key row" as "return the bytes verbatim".

create table email_content_keys (
    -- The email whose body this key decrypts. One key per email; deleting is never
    -- needed (erasure nulls the key in place, preserving the audit that it existed).
    email_id            uuid        primary key references emails (id),

    -- The per-record data key, itself AES-GCM-encrypted under a master key. NULL once
    -- the record has been crypto-shredded: the key is gone, the ciphertext is dead.
    wrapped_dek         bytea,

    -- Which master key version wrapped `wrapped_dek`. Rotation re-wraps the DEK under a
    -- new master and updates this column; retaining the version lets a record be opened
    -- by the exact master key that sealed it.
    master_key_version  text        not null,

    -- The content-encryption scheme, recorded so a future scheme change can be told
    -- apart from today's records without guessing.
    algorithm           text        not null,

    created_at          timestamptz not null default now(),

    -- When the key was destroyed (crypto-shred). NULL while the content is recoverable;
    -- set, with wrapped_dek nulled, once erased. Kept as the durable proof-of-erasure.
    erased_at           timestamptz
);

-- The erasure audit's natural read is "what was erased, and when", newest first.
create index email_content_keys_erased_at_idx
    on email_content_keys (erased_at desc) where erased_at is not null;
