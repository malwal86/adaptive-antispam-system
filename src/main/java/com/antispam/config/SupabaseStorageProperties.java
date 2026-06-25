package com.antispam.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Coordinates for the Supabase Storage bucket the retrain loop stages model candidates into
 * (story 10.04), bound from the {@code app.supabase} prefix:
 *
 * <ul>
 *   <li>{@code APP_SUPABASE_URL} — project base URL ({@code https://<ref>.supabase.co}); blank
 *       when no remote storage is configured (local/dev/tests)</li>
 *   <li>{@code APP_SUPABASE_KEY} — service-role key with read access to Storage</li>
 *   <li>{@code APP_SUPABASE_BUCKET} — the bucket candidates are uploaded to (default {@code models})</li>
 * </ul>
 *
 * <p>Unlike {@link RequiredServicesProperties}, these are deliberately <b>not</b> {@code @NotBlank}:
 * the system must boot and serve the classpath-bundled bootstrap model with no Supabase configured at
 * all. Only when a promoted candidate has to be fetched does a blank URL become an error — surfaced
 * then, at the fetch, as a {@code ModelArtifactNotFoundException}, not as a startup failure that would
 * stop a perfectly serviceable bootstrap-only deployment from booting. The matching
 * {@code SupabaseModelArtifactStore} bean is itself conditional on {@code app.supabase.url} being set,
 * so a blank URL means "no remote store", not "half-configured".
 *
 * @param url        Supabase project base URL, or blank when no remote storage is configured
 * @param serviceKey service-role key used as the bearer token on Storage reads
 * @param bucket     the Storage bucket retrain candidates live in
 */
@ConfigurationProperties(prefix = "app.supabase")
public record SupabaseStorageProperties(String url, String serviceKey, String bucket) {

    /** Whether a remote Supabase Storage backend is configured (a non-blank base URL). */
    public boolean isConfigured() {
        return url != null && !url.isBlank();
    }
}
