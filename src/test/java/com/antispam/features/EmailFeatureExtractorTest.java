package com.antispam.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.antispam.features.FeatureSet.AuthFeatures;
import com.antispam.features.FeatureSet.HeaderFeatures;
import com.antispam.features.FeatureSet.LinkFeatures;
import com.antispam.features.FeatureSet.TextFeatures;
import com.antispam.features.FeatureSet.TimingFeatures;
import com.antispam.ingest.Email;
import com.antispam.ingest.ParsedEmail;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure, deterministic feature extractors. Each concern is
 * exercised on its own with plain inputs (the values the tests assert are
 * computed by hand, not echoed from the implementation), plus the facade is
 * checked end-to-end on raw bytes including malformed input and determinism.
 */
class EmailFeatureExtractorTest {

    @Nested
    class TextStats {

        @Test
        void counts_chars_words_and_exclamations() {
            TextFeatures f = EmailFeatureExtractor.textFeatures("Hello world!!");

            assertThat(f.charCount()).isEqualTo(13);
            assertThat(f.wordCount()).isEqualTo(2);
            assertThat(f.exclamationCount()).isEqualTo(2);
            // "Helloworld" → 10 letters, 'H' uppercase ⇒ 1/10.
            assertThat(f.uppercaseRatio()).isEqualTo(0.1);
            // tokens "Hello" (5) and "world!!" (7) ⇒ mean 6.0.
            assertThat(f.avgWordLength()).isEqualTo(6.0);
        }

        @Test
        void all_caps_body_has_uppercase_ratio_of_one() {
            TextFeatures f = EmailFeatureExtractor.textFeatures("BUY NOW");
            assertThat(f.uppercaseRatio()).isEqualTo(1.0);
        }

        @Test
        void empty_body_degrades_to_zeroes_without_dividing_by_zero() {
            TextFeatures f = EmailFeatureExtractor.textFeatures("");
            assertThat(f.charCount()).isZero();
            assertThat(f.wordCount()).isZero();
            assertThat(f.uppercaseRatio()).isZero();
            assertThat(f.exclamationCount()).isZero();
            assertThat(f.avgWordLength()).isZero();
        }

        @Test
        void null_body_is_treated_as_empty() {
            TextFeatures f = EmailFeatureExtractor.textFeatures(null);
            assertThat(f.charCount()).isZero();
            assertThat(f.wordCount()).isZero();
        }
    }

    @Nested
    class Links {

        @Test
        void counts_urls_and_distinct_domains() {
            LinkFeatures f = EmailFeatureExtractor.linkFeatures(
                    "see http://a.example/x and https://a.example/y and http://b.example");

            assertThat(f.urlCount()).isEqualTo(3);
            assertThat(f.uniqueDomainCount()).isEqualTo(2);
            assertThat(f.maxUrlLength()).isEqualTo("https://a.example/y".length());
        }

        @Test
        void flags_raw_ip_hosts() {
            LinkFeatures f = EmailFeatureExtractor.linkFeatures("click http://192.168.0.1/login");
            assertThat(f.hasIpUrl()).isTrue();
            assertThat(f.hasPunycodeDomain()).isFalse();
        }

        @Test
        void flags_punycode_hosts() {
            LinkFeatures f = EmailFeatureExtractor.linkFeatures("go to https://xn--80ak6aa92e.com/");
            assertThat(f.hasPunycodeDomain()).isTrue();
            assertThat(f.hasIpUrl()).isFalse();
        }

        @Test
        void body_without_links_is_all_zero_and_false() {
            LinkFeatures f = EmailFeatureExtractor.linkFeatures("no links at all");
            assertThat(f.urlCount()).isZero();
            assertThat(f.uniqueDomainCount()).isZero();
            assertThat(f.maxUrlLength()).isZero();
            assertThat(f.hasIpUrl()).isFalse();
            assertThat(f.hasPunycodeDomain()).isFalse();
        }

        @Test
        void null_body_yields_empty_link_features() {
            LinkFeatures f = EmailFeatureExtractor.linkFeatures(null);
            assertThat(f.urlCount()).isZero();
        }
    }

    @Nested
    class Auth {

        @Test
        void parses_spf_dkim_dmarc_results() {
            AuthFeatures f = EmailFeatureExtractor.authFeatures(
                    "mx.google.com; spf=pass smtp.mailfrom=x.com; dkim=pass header.d=x.com; dmarc=fail");

            assertThat(f.spf()).isEqualTo("pass");
            assertThat(f.dkim()).isEqualTo("pass");
            assertThat(f.dmarc()).isEqualTo("fail");
        }

        @Test
        void missing_method_is_unknown() {
            AuthFeatures f = EmailFeatureExtractor.authFeatures("mx.google.com; spf=softfail");
            assertThat(f.spf()).isEqualTo("softfail");
            assertThat(f.dkim()).isEqualTo("unknown");
            assertThat(f.dmarc()).isEqualTo("unknown");
        }

        @Test
        void absent_header_is_all_unknown() {
            AuthFeatures f = EmailFeatureExtractor.authFeatures(null);
            assertThat(f.spf()).isEqualTo("unknown");
            assertThat(f.dkim()).isEqualTo("unknown");
            assertThat(f.dmarc()).isEqualTo("unknown");
        }
    }

    @Nested
    class Timing {

        @Test
        void derives_hour_and_day_in_utc() {
            // 2024-03-13 is a Wednesday; 14:30 UTC.
            TimingFeatures f = EmailFeatureExtractor.timingFeatures(Instant.parse("2024-03-13T14:30:00Z"));

            assertThat(f.hasDate()).isTrue();
            assertThat(f.hourOfDayUtc()).isEqualTo(14);
            assertThat(f.dayOfWeek()).isEqualTo(3);
            assertThat(f.weekend()).isFalse();
        }

        @Test
        void flags_weekend() {
            // 2024-03-16 is a Saturday.
            TimingFeatures f = EmailFeatureExtractor.timingFeatures(Instant.parse("2024-03-16T08:00:00Z"));
            assertThat(f.dayOfWeek()).isEqualTo(6);
            assertThat(f.weekend()).isTrue();
        }

        @Test
        void missing_date_degrades_to_null_sentinels() {
            TimingFeatures f = EmailFeatureExtractor.timingFeatures(null);
            assertThat(f.hasDate()).isFalse();
            assertThat(f.hourOfDayUtc()).isNull();
            assertThat(f.dayOfWeek()).isNull();
            assertThat(f.weekend()).isFalse();
        }
    }

    @Nested
    class Headers {

        @Test
        void measures_subject_shouting_and_recipients() {
            HeaderFeatures f = EmailFeatureExtractor.headerFeatures(
                    "FREE MONEY!!", "a@x.com", "b@y.com, c@y.com", null);

            assertThat(f.hasSubject()).isTrue();
            assertThat(f.subjectLength()).isEqualTo(12);
            assertThat(f.subjectUppercaseRatio()).isEqualTo(1.0);
            assertThat(f.subjectExclamationCount()).isEqualTo(2);
            assertThat(f.hasSender()).isTrue();
            assertThat(f.recipientCount()).isEqualTo(2);
            assertThat(f.replyToDiffersFromFrom()).isFalse();
        }

        @Test
        void detects_reply_to_mismatch() {
            HeaderFeatures f = EmailFeatureExtractor.headerFeatures(
                    "hi", "sender@good.com", "me@x.com", "Evil <attacker@evil.com>");
            assertThat(f.replyToDiffersFromFrom()).isTrue();
        }

        @Test
        void reply_to_equal_to_from_is_not_a_mismatch() {
            HeaderFeatures f = EmailFeatureExtractor.headerFeatures(
                    "hi", "sender@good.com", "me@x.com", "Sender <sender@good.com>");
            assertThat(f.replyToDiffersFromFrom()).isFalse();
        }

        @Test
        void all_headers_absent_degrades_gracefully() {
            HeaderFeatures f = EmailFeatureExtractor.headerFeatures(null, null, null, null);
            assertThat(f.hasSubject()).isFalse();
            assertThat(f.subjectLength()).isZero();
            assertThat(f.subjectUppercaseRatio()).isZero();
            assertThat(f.hasSender()).isFalse();
            assertThat(f.recipientCount()).isZero();
            assertThat(f.replyToDiffersFromFrom()).isFalse();
        }
    }

    @Nested
    class Facade {

        @Test
        void extracts_a_full_feature_set_stamped_with_the_current_version() {
            Email email = emailWith(
                    "From: alice@example.com\r\n"
                            + "To: bob@example.com\r\n"
                            + "Subject: Hello there\r\n"
                            + "\r\n"
                            + "Visit https://example.com/welcome today!\r\n",
                    new ParsedEmail("alice@example.com", "example.com", "bob@example.com",
                            "Hello there", Instant.parse("2024-03-13T14:30:00Z"),
                            "mx; spf=pass; dkim=pass; dmarc=pass"));

            EmailFeatures result = new EmailFeatureExtractor().extract(email);

            assertThat(result.emailId()).isEqualTo(email.id());
            assertThat(result.featureVersion()).isEqualTo(EmailFeatureExtractor.FEATURE_VERSION);
            FeatureSet fs = result.features();
            assertThat(fs.text().wordCount()).isGreaterThan(0);
            assertThat(fs.link().urlCount()).isEqualTo(1);
            assertThat(fs.auth().spf()).isEqualTo("pass");
            assertThat(fs.timing().hourOfDayUtc()).isEqualTo(14);
            assertThat(fs.header().hasSubject()).isTrue();
            // The embedding hook is deliberately left empty for Epic 04.03.
            assertThat(fs.embedding()).isNull();
        }

        @Test
        void extraction_is_deterministic_for_the_same_input() {
            Email email = emailWith(
                    "From: a@x.com\r\nSubject: Deal\r\n\r\nGreat DEAL!! http://a.example/x\r\n",
                    new ParsedEmail("a@x.com", "x.com", null, "Deal",
                            Instant.parse("2024-01-01T00:00:00Z"), null));

            FeatureSet first = new EmailFeatureExtractor().extract(email).features();
            FeatureSet second = new EmailFeatureExtractor().extract(email).features();

            assertThat(first).isEqualTo(second);
        }

        @Test
        void malformed_bytes_do_not_crash_and_yield_sentinels() {
            byte[] garbage = {0, 1, 2, 3, (byte) 0xFF, (byte) 0xFE};
            Email email = new Email(UUID.randomUUID(), new byte[] {1}, garbage,
                    new ParsedEmail(null, null, null, null, null, null), "api", null);

            EmailFeatures result = new EmailFeatureExtractor().extract(email);

            assertThat(result.featureVersion()).isEqualTo(EmailFeatureExtractor.FEATURE_VERSION);
            assertThat(result.features().auth().spf()).isEqualTo("unknown");
            assertThat(result.features().timing().hasDate()).isFalse();
            assertThat(result.features().link().urlCount()).isZero();
        }

        @Test
        void reads_links_from_an_html_only_body() {
            Email email = emailWith(
                    "From: a@x.com\r\n"
                            + "Subject: hi\r\n"
                            + "Content-Type: text/html; charset=utf-8\r\n"
                            + "\r\n"
                            + "<html><body><a href=\"http://a.example/x\">click</a></body></html>\r\n",
                    new ParsedEmail("a@x.com", "x.com", null, "hi", null, null));

            FeatureSet fs = new EmailFeatureExtractor().extract(email).features();
            assertThat(fs.link().urlCount()).isEqualTo(1);
        }
    }

    private static Email emailWith(String raw, ParsedEmail metadata) {
        return new Email(UUID.randomUUID(), new byte[] {1}, raw.getBytes(StandardCharsets.UTF_8),
                metadata, "api", null);
    }

    // Guards against accidental precision drift in rounded ratios.
    @Test
    void ratios_are_rounded_to_a_stable_precision() {
        TextFeatures f = EmailFeatureExtractor.textFeatures("Ab c");
        // letters: A,b,c → 1 uppercase / 3 letters = 0.333333...
        assertThat(f.uppercaseRatio()).isCloseTo(0.333333, within(1e-9));
    }
}
