package dev.saseq.services;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The code that fills the collections the caveat is computed from.
 *
 * <p>{@code CoverCounts} and {@code coverCaveat} are pinned clause by clause, but every one of
 * those tests builds its input by hand — so a mistake here leaves them all green while the caveat
 * says something confidently wrong.
 */
class LiveEventDetailsTest {

    private static DataObject event(String id) {
        return DataObject.empty().put("id", id);
    }

    /** The shape Discord's response has, elements and all. */
    private static DataArray live(Object... entries) {
        DataArray array = DataArray.empty();
        for (Object entry : entries) {
            array.add(entry);
        }
        return array;
    }

    @Test
    void anEventWithACoverIsDescribedAndItsUrlKept() {
        LiveEventDetails d = LiveEventDetails.read(live(
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
        LiveEventDetails d = LiveEventDetails.read(live(event("1")));

        assertThat(d.described()).containsExactly("1");
        assertThat(d.covers()).isEmpty();
    }

    @Test
    void aMalformedRecurrenceDoesNotDiscardAGoodCoverOrTheEvent() {
        // The direction that is actually reachable: recurrence_rule as a scalar makes getObject
        // throw. The cover is still read, so the event is described and its URL kept; only its
        // schedule is unknown.
        LiveEventDetails d = LiveEventDetails.read(live(
                event("1").put("recurrence_rule", "weekly").put("image", "aaa")));

        assertThat(d.returned()).containsExactly("1");
        assertThat(d.described()).containsExactly("1");
        assertThat(d.covers()).containsKey("1");
        assertThat(d.recurrenceFailed()).containsExactly("1");
        assertThat(d.rules()).isEmpty();
    }

    @Test
    void aRecurrenceThatParsesButWillNotRenderIsCaughtToo() {
        // RecurrenceRule.of checks only the top-level shape. describe() is what trips over a
        // malformed nested field, so it runs inside the per-entry guard: outside it, at display
        // time, this event costs the whole listing instead of its own schedule line.
        LiveEventDetails d = LiveEventDetails.read(live(
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
        // Discord returned an event and there is no way to say which. Such an entry cannot enter
        // `returned` — a listed copy would then be called missing — so it is counted on its own.
        //
        // The object id is the case that makes this a snowflake check rather than a blank check.
        // DataObject.getString coerces via toString rather than throwing, so it arrives as a
        // string like "{hash=aaa}": recorded, it matches no listed event (every listed id comes
        // from a JDA entity), so it would be reported as an event Discord has and the cache does
        // not, while the real event it belongs to reads as absent. Two wrong claims from one
        // malformed field.
        LiveEventDetails d = LiveEventDetails.read(live(
                DataObject.empty(),
                event("   "),
                event("not-a-snowflake"),
                DataObject.empty().put("id", DataObject.empty().put("hash", "aaa")),
                event("1385996249957662770")));

        assertThat(d.unidentifiable()).isEqualTo(4);
        assertThat(d.returned()).containsExactly("1385996249957662770");
    }

    @Test
    void aNumericIdIsTheCoercionWorthKeeping() {
        // 123 and "123" address the same event, so this one is not a malformed field — and it is
        // the reason the guard above tests the shape rather than the JSON type.
        LiveEventDetails d = LiveEventDetails.read(live(
                DataObject.empty().put("id", 1385996249957662770L)));

        assertThat(d.returned()).containsExactly("1385996249957662770");
        assertThat(d.unidentifiable()).isZero();
    }

    @Test
    void oneUnreadableEntryCostsOnlyItself() {
        // The property the per-entry guards exist for: everything before and after survives. The
        // middle event's recurrence is unreadable, which is the failure that genuinely throws.
        LiveEventDetails d = LiveEventDetails.read(live(
                event("11").put("image", "aaa"),
                event("22").put("recurrence_rule", "not-an-object"),
                event("33").put("image", "bbb")));

        assertThat(d.returned()).containsExactlyInAnyOrder("11", "22", "33");
        assertThat(d.covers()).containsOnlyKeys("11", "33");
        assertThat(d.recurrenceFailed()).containsExactly("22");
    }

    @Test
    void anElementThatIsNotAnEventObjectCostsOnlyItself() {
        // read() walks the array itself. Converting it in the caller puts this element's
        // getObject one level up, outside every per-entry guard, where it discards the whole live
        // read and both good events lose their covers.
        LiveEventDetails d = LiveEventDetails.read(live(
                event("11").put("image", "aaa"),
                "not-an-object",
                event("33").put("image", "bbb")));

        assertThat(d.unidentifiable()).isEqualTo(1);
        assertThat(d.returned()).containsExactlyInAnyOrder("11", "33");
        assertThat(d.covers()).containsOnlyKeys("11", "33");
    }

    @Test
    void aBlankCoverHashIsUnreadableRatherThanACoverAtNoUrl() {
        // "" passes a type check and would build .../{id}/.png — a URL that resolves to nothing,
        // reported as this event's cover. It is not a hash, and it is not "no cover" either: the
        // field says nothing, so nothing is claimed from it.
        LiveEventDetails d = LiveEventDetails.read(live(
                event("11").put("image", ""),
                event("22").put("image", "   ")));

        assertThat(d.returned()).containsExactlyInAnyOrder("11", "22");
        assertThat(d.described()).isEmpty();
        assertThat(d.covers()).isEmpty();
    }

    @Test
    void anImageThatIsNotAStringIsUnreadableRatherThanANonsenseUrl() {
        // getString coerces via toString, so without a typed check this event would be reported as
        // having a cover at a URL built from an object's toString — a claim about the event drawn
        // from a field that could not be read. Unreadable is the honest side of that line: not
        // described, so the caveat counts it rather than the listing printing it.
        LiveEventDetails d = LiveEventDetails.read(live(
                event("1").put("image", DataObject.empty().put("hash", "aaa"))));

        assertThat(d.returned()).containsExactly("1");
        assertThat(d.described()).isEmpty();
        assertThat(d.covers()).isEmpty();
    }

    @Test
    void returnedIdsKeepTheOrderDiscordSentThem() {
        // containsExactly, not containsExactlyInAnyOrder: the caveat names these ids and prints
        // only the first ten of them, so which ten a caller is handed to act on depends on this
        // order holding. Set.copyOf iterates in an order randomised per JVM run from an internal
        // salt — five ids make an accidental pass a 1-in-120 shot, so this is the assertion that
        // catches it rather than one that gets lucky.
        LiveEventDetails d = LiveEventDetails.read(live(
                event("51"), event("52"), event("53"), event("54"), event("55")));

        assertThat(d.returned()).containsExactly("51", "52", "53", "54", "55");
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
