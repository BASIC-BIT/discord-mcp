package dev.saseq.services;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of the listing that had no coverage.
 *
 * <p>{@code CoverCounts} and {@code coverCaveat} are pinned clause by clause, but every one of
 * those tests builds its input by hand — so a mistake in the code that fills these collections
 * leaves them all green while the caveat says something confidently wrong. Three of the cases
 * below are specifically argued for in comments and none of them was reachable by a test before.
 */
class LiveEventDetailsTest {

    private static DataObject event(String id) {
        return DataObject.empty().put("id", id);
    }

    @Test
    void anEventWithACoverIsDescribedAndItsUrlKept() {
        LiveEventDetails d = LiveEventDetails.read(List.of(
                event("1").put("image", "8210694c9d4d01a72fafbdc9012675d1")));

        assertThat(d.returned()).containsExactly("1");
        assertThat(d.described()).containsExactly("1");
        assertThat(d.covers()).containsEntry("1", "https://cdn.discordapp.com/guild-events/1"
                + "/8210694c9d4d01a72fafbdc9012675d1.png");
        assertThat(d.unidentifiable()).isZero();
    }

    @Test
    void anEventWithNoCoverIsStillDescribed() {
        // "Described" means the cover was read, not that there was one. Conflating them is what
        // would turn "we looked and there is none" into "we did not look".
        LiveEventDetails d = LiveEventDetails.read(List.of(event("1")));

        assertThat(d.described()).containsExactly("1");
        assertThat(d.covers()).isEmpty();
    }

    @Test
    void aMalformedRecurrenceDoesNotDiscardAGoodCoverOrTheEvent() {
        // The direction that is actually reachable: recurrence_rule as a scalar makes getObject throw. The cover is
        // still read, so the event is described and its URL kept; only its schedule is unknown.
        LiveEventDetails d = LiveEventDetails.read(List.of(
                event("1").put("recurrence_rule", "weekly").put("image", "aaa")));

        assertThat(d.returned()).containsExactly("1");
        assertThat(d.described()).containsExactly("1");
        assertThat(d.covers()).containsKey("1");
        assertThat(d.recurrenceFailed()).containsExactly("1");
        assertThat(d.rules()).isEmpty();
    }

    @Test
    void aRecurrenceThatParsesButWillNotRenderIsCaughtToo() {
        // recurrenceOf checks only the top-level shape. describe() is what trips over a malformed
        // nested field, and it used to run at display time, outside every guard, taking the whole
        // listing down rather than costing this one event its schedule line.
        LiveEventDetails d = LiveEventDetails.read(List.of(
                event("1").put("recurrence_rule", DataObject.empty()
                        .put("frequency", 2).put("interval", 1)
                        .put("start", "2026-08-05T20:00:00Z")
                        .put("by_weekday", "monday"))));

        assertThat(d.returned()).containsExactly("1");
        assertThat(d.recurrenceFailed()).containsExactly("1");
        assertThat(d.rules()).isEmpty();
        // Its cover was still readable, so it is described.
        assertThat(d.described()).containsExactly("1");
    }

    @Test
    void anEntryWithNoUsableIdIsCountedAndNotGuessedAt() {
        // Missing and blank both mean the same thing: Discord returned an event and there is no
        // way to say which. Such an entry cannot enter `returned` — a listed copy would then be
        // called missing — so it is counted on its own.
        //
        // Note what is NOT here: a numeric or object id. DataObject.getString coerces via
        // toString rather than throwing, so those arrive as usable ids. Comments in this codebase
        // claimed otherwise until this test disproved it.
        LiveEventDetails d = LiveEventDetails.read(List.of(
                DataObject.empty(),
                event("   "),
                event("real")));

        assertThat(d.unidentifiable()).isEqualTo(2);
        assertThat(d.returned()).containsExactly("real");
    }

    @Test
    void oneUnreadableEntryCostsOnlyItself() {
        // The property the per-entry guards exist for: everything before and after survives. The
        // middle event's recurrence is unreadable, which is the failure that genuinely throws.
        LiveEventDetails d = LiveEventDetails.read(List.of(
                event("before").put("image", "aaa"),
                event("bad").put("recurrence_rule", "not-an-object"),
                event("after").put("image", "bbb")));

        assertThat(d.returned()).containsExactlyInAnyOrder("before", "bad", "after");
        assertThat(d.covers()).containsOnlyKeys("before", "after");
        assertThat(d.recurrenceFailed()).containsExactly("bad");
    }

    @Test
    void anUnreadResponseClaimsNothing() {
        LiveEventDetails d = LiveEventDetails.unread();

        assertThat(d.returned()).isEmpty();
        assertThat(d.described()).isEmpty();
        assertThat(d.covers()).isEmpty();
        assertThat(d.rules()).isEmpty();
        assertThat(d.recurrenceFailed()).isEmpty();
        assertThat(d.unidentifiable()).isZero();
    }
}
