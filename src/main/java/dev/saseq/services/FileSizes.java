package dev.saseq.services;

import java.util.Locale;

/**
 * One spelling of "how big is this file" for messages a caller reads.
 *
 * <p>{@code MessageService} and {@code UserService} each carried a private copy, and a third was
 * nearly added alongside them. Its own class rather than a method on {@link LocalFileGuard}: that
 * class confines caller-supplied filesystem reads, most of its callers here touch no filesystem at
 * all, and a security guard is the one place where unrelated helpers should not accumulate — what
 * it does has to stay answerable in a sentence.
 */
final class FileSizes {

    private FileSizes() {
    }

    /**
     * Never integer division. The download path already shipped "exceeded the 0 MB allowed for
     * it", which reads as a bug rather than a limit, and a whole-units formatter would report a
     * 1.9 MB cover as "1 MB" beside a limit quoted in MB.
     *
     * <p>{@link Locale#ROOT} because the default would render "5,0 MB" on a comma-decimal host,
     * making an error message vary by deployment and quietly breaking any assertion that pins it.
     *
     * <p>{@code long} rather than {@code int}: a per-call download total can exceed
     * {@code Integer.MAX_VALUE} even though no single attachment can, and widening is
     * source-compatible with the callers that pass an {@code int}.
     */
    static String format(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
