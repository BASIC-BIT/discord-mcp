package dev.saseq.services;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of the listing caveat that was not tested.
 *
 * <p>{@code coverCaveat} was extracted and pinned clause by clause because every clause is a claim
 * about what the reader is looking at. The arithmetic producing those numbers had no coverage, and
 * it is where the subtle mistakes are: the absent/terminal split, and unlisted as "returned minus
 * those actually listed". A transposition there produces confidently wrong text and no failure,
 * because the formatter's tests call it directly.
 */
class CoverCountsTest {

    private static final Set<String> NONE = Set.of();

    @Test
    void anEventWhoseCoverWasReadIsDescribed() {
        CoverCounts c = CoverCounts.tally(List.of("a", "b"), NONE, Set.of("a", "b"),
                Set.of("a", "b"), Set.of("a"), NONE, 0);

        assertThat(c.described()).isEqualTo(2);
        // b was read and has no cover; that is information, not a gap.
        assertThat(c.coverless()).isEqualTo(1);
        assertThat(c.unreadable()).isZero();
        assertThat(c.absent()).isZero();
    }

    @Test
    void returnedButUnparseableIsNotAbsent() {
        // Discord did send this event. Counting it as absent blames a cache gap that did not
        // happen and sends the reader to look in the wrong place.
        CoverCounts c = CoverCounts.tally(List.of("a"), NONE, Set.of("a"), NONE, NONE, NONE, 0);

        assertThat(c.unreadable()).isEqualTo(1);
        assertThat(c.absent()).isZero();
        assertThat(c.described()).isZero();
    }

    @Test
    void anEndedEventIsNotCountedAsAGap() {
        // GET /guilds/{id}/scheduled-events carries scheduled and active events only, so a
        // finished one is legitimately missing. Counting it as absent would put a caveat about
        // read gaps on most listings.
        CoverCounts c = CoverCounts.tally(List.of("over", "live"), Set.of("over"), Set.of("live"),
                Set.of("live"), Set.of("live"), NONE, 0);

        assertThat(c.terminal()).isEqualTo(1);
        assertThat(c.absent()).isZero();
        assertThat(c.described()).isEqualTo(1);
        assertThat(c.coverless()).isZero();
    }

    @Test
    void anEndedEventThatDiscordStillReturnsIsJustDescribed() {
        // Terminal only matters when the live read omitted it. If Discord still returns it, its
        // cover is readable and there is nothing to explain.
        CoverCounts c = CoverCounts.tally(List.of("over"), Set.of("over"), Set.of("over"),
                Set.of("over"), Set.of("over"), NONE, 0);

        assertThat(c.terminal()).isZero();
        assertThat(c.described()).isEqualTo(1);
    }

    @Test
    void unlistedCountsWhatDiscordHasAndTheListingDoesNot() {
        // Returned minus those actually in the listing. These have no row at all, so there is
        // nowhere to hang a per-event note — the count is the only place it can be said.
        CoverCounts c = CoverCounts.tally(List.of("a"), NONE, Set.of("a", "ghost1", "ghost2"),
                Set.of("a"), Set.of("a"), NONE, 0);

        assertThat(c.unlisted()).isEqualTo(2);
        assertThat(c.described()).isEqualTo(1);
        assertThat(c.absent()).isZero();
    }

    @Test
    void aListedEventDiscordNeverReturnedIsAbsent() {
        CoverCounts c = CoverCounts.tally(List.of("a", "gone"), NONE, Set.of("a"), Set.of("a"),
                Set.of("a"), NONE, 0);

        assertThat(c.absent()).isEqualTo(1);
        assertThat(c.unreadable()).isZero();
        assertThat(c.terminal()).isZero();
    }

    @Test
    void recurrenceFailureIsCountedIndependentlyOfTheCover() {
        // A readable cover beside an unparseable recurrence: the event is described, and the
        // missing "Recurs:" line still needs explaining.
        CoverCounts c = CoverCounts.tally(List.of("a"), NONE, Set.of("a"), Set.of("a"),
                Set.of("a"), Set.of("a"), 0);

        assertThat(c.described()).isEqualTo(1);
        assertThat(c.coverless()).isZero();
        assertThat(c.recurrenceUnreadable()).isEqualTo(1);
    }

    @Test
    void recurrenceFailureForAnEventNotInTheListingIsNotCounted() {
        // The clause says the event "shows no schedule below". An event with no row below cannot
        // support that, which is why this is keyed off the listed ids rather than counted raw.
        CoverCounts c = CoverCounts.tally(List.of("a"), NONE, Set.of("a", "ghost"), Set.of("a"),
                Set.of("a"), Set.of("ghost"), 0);

        assertThat(c.recurrenceUnreadable()).isZero();
        assertThat(c.unlisted()).isEqualTo(1);
    }

    @Test
    void anEventDiscordDidNotReturnHasAnUnknownScheduleToo() {
        // Absent and terminal events are usually discussed for their covers, but nothing read
        // their recurrence either — and their row renders exactly like a one-off: no "Recurs:"
        // line at all. Counting only the parse failures let a weekly event that Discord did not
        // return read as a one-off, which is the confusion this recurrence read exists to remove.
        CoverCounts absent = CoverCounts.tally(List.of("a", "gone"), NONE, Set.of("a"), Set.of("a"),
                Set.of("a"), Set.of(), 0);
        assertThat(absent.absent()).isEqualTo(1);
        assertThat(absent.recurrenceUnreadable()).isEqualTo(1);

        CoverCounts over = CoverCounts.tally(List.of("a", "done"), Set.of("done"), Set.of("a"),
                Set.of("a"), Set.of("a"), Set.of(), 0);
        assertThat(over.terminal()).isEqualTo(1);
        assertThat(over.recurrenceUnreadable()).isEqualTo(1);

        // And an event that was read is not counted twice or wrongly: its schedule is known.
        assertThat(CoverCounts.tally(List.of("a"), NONE, Set.of("a"), Set.of("a"), Set.of("a"),
                Set.of(), 0).recurrenceUnreadable()).isZero();
    }

    @Test
    void unidentifiableEntriesPassThroughUncounted() {
        // They belong to no event in either direction, so they cannot be folded into any of the
        // per-event tallies — the caveat states them on their own.
        CoverCounts c = CoverCounts.tally(List.of("a"), NONE, Set.of("a"), Set.of("a"),
                Set.of("a"), NONE, 3);

        assertThat(c.unidentifiable()).isEqualTo(3);
        assertThat(c.absent()).isZero();
        assertThat(c.unreadable()).isZero();
    }

    @Test
    void everyListedEventLandsInExactlyOneBucket() {
        // described, unreadable, terminal and absent partition the listing. If they ever stop
        // doing so, the caveat is either double-counting events or losing them silently.
        CoverCounts c = CoverCounts.tally(
                List.of("described", "unreadable", "over", "gone"),
                Set.of("over"),
                Set.of("described", "unreadable"),
                Set.of("described"),
                Set.of("described"),
                NONE, 0);

        assertThat(c.described() + c.unreadable() + c.terminal() + c.absent())
                .as("the four per-event buckets must cover every listed event exactly once")
                .isEqualTo(4);
        assertThat(c.described()).isEqualTo(1);
        assertThat(c.unreadable()).isEqualTo(1);
        assertThat(c.terminal()).isEqualTo(1);
        assertThat(c.absent()).isEqualTo(1);
    }
}
