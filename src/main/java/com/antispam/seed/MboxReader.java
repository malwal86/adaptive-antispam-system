package com.antispam.seed;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Normalizes a corpus file into the individual raw RFC-822 messages the ingest
 * path expects. The public seeds come in two shapes:
 *
 * <ul>
 *   <li>One message per file (SpamAssassin {@code .eml}, Enron maildir files) —
 *       passed through unchanged.</li>
 *   <li>Many messages in one {@code .mbox} file (common for phishing corpora) —
 *       split on the {@code From } separator lines.</li>
 * </ul>
 *
 * <p>Splitting is mboxo-style: a line beginning with {@code "From "} at column 0
 * starts a new message and is itself dropped (it is the mailbox separator, not
 * part of the message). Body lines that genuinely start with "From " are rare in
 * the seed corpora and are accepted as a known, documented limitation rather than
 * implementing full mboxrd unescaping for a load-time tool.
 */
@Component
public class MboxReader {

    /**
     * @param content  the raw bytes of one corpus file
     * @param filename the file name, used only to detect the {@code .mbox} format
     * @return one entry per message; a non-mbox file yields exactly one message
     */
    public List<byte[]> messages(byte[] content, String filename) {
        if (filename.toLowerCase(Locale.ROOT).endsWith(".mbox")) {
            return splitMbox(content);
        }
        return List.of(content);
    }

    private static List<byte[]> splitMbox(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        List<byte[]> messages = new ArrayList<>();
        StringBuilder current = null;
        // -1 keeps trailing empty fields so message boundaries are faithful.
        for (String line : text.split("\n", -1)) {
            if (isSeparator(line)) {
                flush(messages, current);
                current = new StringBuilder();
            } else if (current != null) {
                current.append(line).append('\n');
            }
            // Lines before the first separator are mailbox preamble: ignored.
        }
        flush(messages, current);
        return messages;
    }

    /** An mbox "From_" separator line, tolerant of a trailing CR (CRLF files). */
    private static boolean isSeparator(String line) {
        String stripped = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        return stripped.startsWith("From ");
    }

    private static void flush(List<byte[]> messages, StringBuilder message) {
        if (message == null) {
            return;
        }
        byte[] bytes = message.toString().strip().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0) {
            messages.add(bytes);
        }
    }
}
