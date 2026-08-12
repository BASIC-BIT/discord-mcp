package dev.saseq.services;

import java.util.Locale;

/**
 * One spelling of "how big is this file" for messages a caller reads.
 *
 * <p>Its own class rather than a method on {@link LocalFileGuard}, which confines caller-supplied
 * filesystem reads: a security guard is the one place unrelated helpers should not accumulate.
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
        String[] units = {"KB", "MB", "GB"};
        double scaled = bytes / 1024.0;
        int unit = 0;
        // Carried on what will be printed, not on the raw value: 1048575 B is 1023.999 KB, which
        // renders as "1024.0 KB" — a number that reads as a unit nobody carried, and does so
        // beside a limit quoted in the next unit up. That is the same confusing arithmetic this
        // class was pulled out to stop.
        while (unit < units.length - 1 && Math.round(scaled * 10) >= 10240) {
            scaled /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", scaled, units[unit]);
    }
}
