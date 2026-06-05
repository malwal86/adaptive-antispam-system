package com.antispam.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The redaction utility masks the local-part of email addresses while keeping
 * the domain (the domain is the reputation key downstream, and far less
 * identifying than the local-part). It must be null-safe and never leak a
 * local-part it failed to parse.
 */
class RedactionTest {

    @Test
    void masks_local_part_and_keeps_domain() {
        assertThat(Redaction.maskAddress("alice@example.com")).isEqualTo("a***@example.com");
        assertThat(Redaction.maskAddress("newsletter@deals.example.net"))
                .isEqualTo("n***@deals.example.net");
    }

    @Test
    void masks_plus_addressing_within_the_local_part() {
        assertThat(Redaction.maskAddress("john+spam@gmail.com")).isEqualTo("j***@gmail.com");
    }

    @Test
    void preserves_domain_casing() {
        assertThat(Redaction.maskAddress("UPPER@Example.COM")).isEqualTo("U***@Example.COM");
    }

    @Test
    void fully_masks_a_single_character_local_part() {
        assertThat(Redaction.maskAddress("a@b.com")).isEqualTo("***@b.com");
    }

    @Test
    void returns_null_for_null_or_blank() {
        assertThat(Redaction.maskAddress(null)).isNull();
        assertThat(Redaction.maskAddress("   ")).isNull();
    }

    @Test
    void does_not_leak_a_non_address() {
        assertThat(Redaction.maskAddress("notanemail")).isEqualTo("***");
    }

    @Test
    void redacts_every_address_in_free_form_text_keeping_other_text() {
        assertThat(Redaction.redactEmails("To: victim@recipient.org, bob@x.org"))
                .isEqualTo("To: v***@recipient.org, b***@x.org");
    }

    @Test
    void redact_emails_is_null_safe_and_leaves_address_free_text_untouched() {
        assertThat(Redaction.redactEmails(null)).isNull();
        assertThat(Redaction.redactEmails("no addresses here")).isEqualTo("no addresses here");
    }
}
