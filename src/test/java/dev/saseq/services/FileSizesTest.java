package dev.saseq.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two mistakes this class exists because of, pinned.
 *
 * <p>Both already shipped in the copies it replaced: integer division rendering a limit as
 * "0 MB", and a default locale rendering "5,0 MB" on a comma-decimal host, which varies an error
 * message by deployment and breaks every assertion that quotes one.
 */
class FileSizesTest {

    @Test
    void unitsChangeAtTheBoundaryAndNotBefore() {
        assertThat(FileSizes.format(0)).isEqualTo("0 B");
        assertThat(FileSizes.format(1023)).isEqualTo("1023 B");
        assertThat(FileSizes.format(1024)).isEqualTo("1.0 KB");
        // Not "1024.0 KB": a value that rounds to a full unit is carried, since printing a
        // thousand of a unit next to a limit quoted in the next one up is what this class exists
        // to stop. The band is narrow — 52 bytes — and it is the band a 5 MB limit sits beside.
        assertThat(FileSizes.format(1024L * 1024 - 1)).isEqualTo("1.0 MB");
        assertThat(FileSizes.format(1024L * 1024 - 53)).isEqualTo("1023.9 KB");
        assertThat(FileSizes.format(1024L * 1024)).isEqualTo("1.0 MB");
        assertThat(FileSizes.format(1024L * 1024 * 1024)).isEqualTo("1.0 GB");
    }

    @Test
    void aSizeUnderAWholeUnitIsNotRoundedToZero() {
        // "exceeded the 0 MB allowed for it" is what integer division produced, which reads as a
        // bug rather than a limit — and a 1.9 MB cover reported as "1 MB" beside a limit quoted
        // in MB tells the caller to do nothing.
        assertThat(FileSizes.format(5L * 1024 * 1024)).isEqualTo("5.0 MB");
        assertThat(FileSizes.format(1024L * 1024 * 19 / 10)).isEqualTo("1.9 MB");
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void theDecimalSeparatorDoesNotFollowTheHost() {
        // Declared, not just restored in finally: the only way to prove Locale.ROOT is in force is
        // to change the default, which is process-wide. Harmless today because Surefire runs
        // serially here, and the annotation is what keeps it harmless if that ever changes.
        // A comma-decimal default would render "5,0 MB" here, so an operator in Berlin and one in
        // Chicago would read different text out of the same failure.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThat(FileSizes.format(5L * 1024 * 1024)).isEqualTo("5.0 MB");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void aTotalLargerThanAnIntIsNotTruncated() {
        // A per-call download total can exceed Integer.MAX_VALUE even though no single attachment
        // can, which is why the parameter is a long.
        assertThat(FileSizes.format(3L * 1024 * 1024 * 1024)).isEqualTo("3.0 GB");
    }
}
