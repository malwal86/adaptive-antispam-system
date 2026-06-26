package com.antispam.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The PII scrubber that runs over email content before it leaves our boundary for
 * the external LLM (story 14.03): it must remove direct identifiers — addresses,
 * phone numbers, obvious card/account numbers — while preserving the structure the
 * model needs to judge spam/phishing (URLs, brand mentions, urgency language).
 */
class PiiMaskerTest {

    @Test
    void masks_email_addresses_keeping_the_domain() {
        assertThat(PiiMasker.mask("contact alice@example.com today"))
                .isEqualTo("contact a***@example.com today");
    }

    @Test
    void masks_phone_numbers() {
        assertThat(PiiMasker.mask("call 1-800-555-0199 now")).isEqualTo("call [phone] now");
        assertThat(PiiMasker.mask("ring +44 20 7946 0958")).isEqualTo("ring [phone]");
        assertThat(PiiMasker.mask("dial (234) 567-8900")).isEqualTo("dial [phone]");
    }

    @Test
    void masks_card_like_numbers() {
        assertThat(PiiMasker.mask("card 4111 1111 1111 1111 expires"))
                .isEqualTo("card [card-number] expires");
        assertThat(PiiMasker.mask("acct 4111-1111-1111-1111")).isEqualTo("acct [card-number]");
    }

    @Test
    void preserves_urls_even_when_they_contain_digits() {
        String text = "verify at https://paypal-secure.example.com/login/12345678901234";
        assertThat(PiiMasker.mask(text)).isEqualTo(text);
    }

    @Test
    void preserves_brand_mentions_and_urgency_language() {
        String text = "URGENT! Your PayPal account needs verification immediately";
        assertThat(PiiMasker.mask(text)).isEqualTo(text);
    }

    @Test
    void masks_a_mixed_message_but_keeps_the_phishing_signal() {
        String text = "URGENT: PayPal alert. Verify at https://paypal.example.com or call "
                + "1-800-555-0199. Card 4111 1111 1111 1111. Reply admin@scam.example.";
        String masked = PiiMasker.mask(text);

        assertThat(masked)
                .contains("URGENT")
                .contains("PayPal")
                .contains("https://paypal.example.com")
                .contains("[phone]")
                .contains("[card-number]")
                .contains("a***@scam.example")
                .doesNotContain("1-800-555-0199")
                .doesNotContain("4111 1111 1111 1111")
                .doesNotContain("admin@scam.example.");
    }

    @Test
    void does_not_mask_short_numbers_like_prices_or_years() {
        String text = "Order 12 for $50 in 2024";
        assertThat(PiiMasker.mask(text)).isEqualTo(text);
    }

    @Test
    void is_idempotent() {
        String text = "call 1-800-555-0199 or email bob@example.com, card 4111 1111 1111 1111";
        String once = PiiMasker.mask(text);
        assertThat(PiiMasker.mask(once)).isEqualTo(once);
    }

    @Test
    void is_null_and_empty_safe() {
        assertThat(PiiMasker.mask(null)).isNull();
        assertThat(PiiMasker.mask("")).isEmpty();
        assertThat(PiiMasker.mask("   ")).isEqualTo("   ");
    }
}
