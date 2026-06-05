package com.antispam.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MboxReaderTest {

    private final MboxReader reader = new MboxReader();

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void passes_a_single_eml_through_unchanged() {
        byte[] eml = bytes("From: a@x.example\nSubject: hi\n\nbody");

        var messages = reader.messages(eml, "msg.eml");

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isEqualTo(eml);
    }

    @Test
    void treats_a_non_mbox_extension_as_one_message_even_if_it_starts_with_From() {
        assertThat(reader.messages(bytes("From here it began, not a header"), "note.txt")).hasSize(1);
    }

    @Test
    void splits_an_mbox_into_its_messages_dropping_the_separator_lines() {
        byte[] mbox = bytes("""
                From sender1 Mon Jan 1 00:00:00 2024
                From: a@x.example
                Subject: one

                body one
                From sender2 Mon Jan 1 00:01:00 2024
                From: b@y.example
                Subject: two

                body two
                """);

        var messages = reader.messages(mbox, "batch.mbox");

        assertThat(messages).hasSize(2);
        assertThat(str(messages.get(0)))
                .contains("Subject: one")
                .contains("From: a@x.example")
                .doesNotContain("From sender1");
        assertThat(str(messages.get(1))).contains("Subject: two");
    }

    @Test
    void ignores_preamble_before_the_first_separator() {
        byte[] mbox = bytes("ignored preamble line\nFrom s Mon\nSubject: real\n\nbody\n");

        var messages = reader.messages(mbox, "x.mbox");

        assertThat(messages).hasSize(1);
        assertThat(str(messages.get(0))).startsWith("Subject: real");
    }

    @Test
    void tolerates_crlf_separator_lines() {
        byte[] mbox = bytes("From s Mon\r\nSubject: a\r\n\r\nbody\r\n");

        assertThat(reader.messages(mbox, "x.mbox")).hasSize(1);
    }

    @Test
    void drops_empty_messages_between_adjacent_separators() {
        byte[] mbox = bytes("From s1\n\nFrom s2\nSubject: only\n\nbody\n");

        var messages = reader.messages(mbox, "x.mbox");

        assertThat(messages).hasSize(1);
        assertThat(str(messages.get(0))).contains("Subject: only");
    }
}
