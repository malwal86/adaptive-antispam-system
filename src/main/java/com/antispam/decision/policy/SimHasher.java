package com.antispam.decision.policy;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * A 64-bit SimHash content fingerprinter (story 06.02): the cheap-tier near-duplicate signal that
 * catches templated blasts with trivial variation without paying for embeddings on the fast path
 * (PRD §Subsystem 4 runtime layer). SimHash is locality-sensitive — texts that differ only trivially
 * (whitespace, a swapped name) produce fingerprints a small {@link #hammingDistance Hamming distance}
 * apart, while unrelated texts land roughly half the bits apart — so near-duplicates are recognized
 * by bit distance rather than exact equality. Reworded variants that defeat surface fingerprints are
 * the expensive offline tier's job (06.03 embeddings); this tier is deliberately surface-only.
 *
 * <p><b>Pure and deterministic.</b> The fingerprint is a function of the text alone — no clock, no
 * randomness, no I/O — so the same body always yields the same fingerprint and the comparison is
 * reproducible. Tokenisation is lower-cased alphanumeric runs; each token votes ±1 on every bit
 * according to its 64-bit FNV-1a hash, and a bit is set when its vote sum is positive (the standard
 * SimHash construction). Empty or token-free text yields {@code 0}, which the detector reads as
 * "no fingerprint" and skips, so blank bodies are never grouped as duplicates of one another.
 */
@Component
public class SimHasher {

    /** Lower-cased alphanumeric token runs — punctuation and whitespace are separators. */
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");

    private static final int BITS = 64;

    // FNV-1a 64-bit constants — a fast, well-distributed, deterministic string hash.
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /**
     * The 64-bit SimHash fingerprint of {@code text}, or {@code 0} when it has no tokens (null,
     * blank, or punctuation-only) — a sentinel the detector treats as "no fingerprint".
     */
    public long fingerprint(String text) {
        if (text == null) {
            return 0L;
        }
        int[] voteSum = new int[BITS];
        boolean anyToken = false;
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            anyToken = true;
            long tokenHash = hash64(matcher.group());
            for (int bit = 0; bit < BITS; bit++) {
                if ((tokenHash & (1L << bit)) != 0) {
                    voteSum[bit]++;
                } else {
                    voteSum[bit]--;
                }
            }
        }
        if (!anyToken) {
            return 0L;
        }
        long fingerprint = 0L;
        for (int bit = 0; bit < BITS; bit++) {
            if (voteSum[bit] > 0) {
                fingerprint |= (1L << bit);
            }
        }
        return fingerprint;
    }

    /**
     * The Hamming distance between two fingerprints — the number of differing bits, in {@code [0,64]}.
     * Near-duplicates are within a small distance; unrelated texts are near 32.
     */
    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    private static long hash64(String token) {
        long hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < token.length(); i++) {
            hash ^= token.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
