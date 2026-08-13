package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.exceptions.ParsingException;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScheduledEventServiceTest {

    private ScheduledEventService service;

    /**
     * A guild that resolves, so the guildId check is not what a test trips over.
     *
     * <p>setScheduledEventImage validates the ids, then reads the source, then reads the event
     * from Discord. A test passing a null guildId stops at "guildId cannot be null" and never
     * exercises what it claims to — which is how an assertion ends up passing for a reason it did
     * not name. The source guards are reachable because the event read comes after them; nothing
     * here can reach the write, which needs a real REST call.
     *
     * <p>Which is why several tests here pin "Could not reach Discord to read that event": that
     * message is an artifact of {@code RestActionImpl} casting its {@code JDA} argument to
     * {@code JDAImpl}, which a Mockito proxy is not. What those tests establish is the ordering
     * in their names — everything before the event read ran, and the call stopped there. If a JDA
     * upgrade ever accepts the interface, they stop testing ordering and start attempting real
     * requests, and the failure is a hang rather than a clean red.
     */
    private static final String GUILD = "480695542155051010";
    private static final String EVENT = "1385996249957662770";

    private Guild guild;

    @BeforeEach
    void setUp() {
        JDA jda = mock(JDA.class);
        guild = mock(Guild.class);
        ScheduledEvent event = mock(ScheduledEvent.class);
        lenient().when(jda.getGuildById(GUILD)).thenReturn(guild);
        lenient().when(guild.getId()).thenReturn(GUILD);
        lenient().when(guild.getScheduledEventById(EVENT)).thenReturn(event);
        lenient().when(event.getId()).thenReturn(EVENT);
        lenient().when(event.getName()).thenReturn("Community Night");
        service = new ScheduledEventService(jda);
    }

    @Test
    void movingTheStartCarriesTheEndAlongAndKeepsTheDuration() {
        // The case that produced this: three weekly classes shunted four weeks out. Discord
        // rejects a start that lands after the stored end, and the rejection does not say so.
        DataObject event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        OffsetDateTime end = service.resolveEndTime(event, "2026-09-02T20:00:00-05:00", null);

        assertThat(end).isEqualTo(OffsetDateTime.parse("2026-09-02T21:30:00-05:00"));
    }

    @Test
    void theDurationComesFromTheLiveResponseNotACachedEntity() {
        // Discord normalises the timestamps it returns to UTC, so the live values routinely
        // differ textually from what anyone sent. A 90-minute event must shift by 90 minutes
        // regardless of how its times are spelled — and reading them from here rather than from
        // JDA's cache is what makes that true after an out-of-band edit.
        DataObject event = event("2026-08-06T01:00:00+00:00", "2026-08-06T02:30:00+00:00");

        OffsetDateTime end = service.resolveEndTime(event, "2026-09-02T20:00:00-05:00", null);

        assertThat(Duration.between(OffsetDateTime.parse("2026-09-02T20:00:00-05:00"), end))
                .isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void anExplicitEndTimeWins() {
        DataObject event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        OffsetDateTime end = service.resolveEndTime(
                event, "2026-09-02T20:00:00-05:00", "2026-09-02T23:00:00-05:00");

        assertThat(end).isEqualTo(OffsetDateTime.parse("2026-09-02T23:00:00-05:00"));
    }

    @Test
    void anExplicitEndTimeAppliesWithoutMovingTheStart() {
        // Lengthening an event in place: no start move, so no delta to apply, but the caller's
        // end must still be honoured.
        DataObject event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        OffsetDateTime end = service.resolveEndTime(event, null, "2026-08-05T22:00:00-05:00");

        assertThat(end).isEqualTo(OffsetDateTime.parse("2026-08-05T22:00:00-05:00"));
    }

    @Test
    void anEndAtOrBeforeTheNewStartIsRejectedHereRatherThanByDiscord() {
        // Sending this fails the whole manager update with the opaque server-side error this
        // parameter exists to stop people running into.
        DataObject event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        assertThatThrownBy(() -> service.resolveEndTime(
                event, "2026-09-02T20:00:00-05:00", "2026-09-02T19:00:00-05:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not after the start time")
                .hasMessageContaining("omit it and it will follow the start automatically");

        // Equal is not "after" either: a zero-length event is rejected the same way.
        assertThatThrownBy(() -> service.resolveEndTime(
                event, "2026-09-02T20:00:00-05:00", "2026-09-02T20:00:00-05:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not after the start time");
    }

    @Test
    void anEndOnlyEditIsCheckedAgainstTheExistingStart() {
        DataObject event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        assertThatThrownBy(() -> service.resolveEndTime(event, null, "2026-08-05T19:00:00-05:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not after the start time")
                .hasMessageContaining("Pass scheduledStartTime too");
    }

    @Test
    void anEditThatTouchesNeitherTimeLeavesTheEndAlone() {
        DataObject event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        assertThat(service.resolveEndTime(event, null, null)).isNull();
        assertThat(service.resolveEndTime(event, "", "")).isNull();
    }

    @Test
    void anEventWithNoEndTimeDoesNotGainOne() {
        // Stage and voice events have no end time. Inventing one would impose a constraint the
        // event did not have, and Discord would start enforcing it.
        DataObject event = event("2026-08-05T20:00:00-05:00", null);

        assertThat(service.resolveEndTime(event, "2026-09-02T20:00:00-05:00", null)).isNull();
    }

    @Test
    void durationIsPreservedAcrossADaylightSavingBoundary() {
        // 2026-11-01 is when US clocks go back. A 90-minute class moved across it should still
        // run 90 minutes — shifting the wall-clock end instead would make it 30 minutes longer.
        DataObject event = event("2026-10-28T20:00:00-05:00", "2026-10-28T21:30:00-05:00");

        OffsetDateTime end = service.resolveEndTime(event, "2026-11-04T20:00:00-06:00", null);

        assertThat(end).isEqualTo(OffsetDateTime.parse("2026-11-04T21:30:00-06:00"));
        assertThat(Duration.between(OffsetDateTime.parse("2026-11-04T20:00:00-06:00"), end))
                .isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void aMalformedEndTimeIsRejectedRatherThanIgnored() {
        DataObject event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        assertThatThrownBy(() -> service.resolveEndTime(event, null, "next tuesday"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid ISO8601 timestamp");
    }

    @Test
    void anUnreadableEventStillAcceptsAnEndOnlyEdit() {
        // The empty object the caller substitutes when a best-effort read failed. Validation is
        // skipped rather than the edit refused: the read is only best-effort in the cases that
        // do not move the start, and Discord remains the backstop.
        assertThat(service.resolveEndTime(DataObject.empty(), null, "2026-08-05T22:00:00-05:00"))
                .isEqualTo(OffsetDateTime.parse("2026-08-05T22:00:00-05:00"));
    }

    @Test
    void theCoverUrlIsBuiltFromTheLiveEventObject() {
        // Read from the raw response rather than ScheduledEvent.getImageUrl() so that a cover
        // changed out of band is reported as it actually is, not as JDA last cached it.
        DataObject raw = DataObject.empty().put("image", "8210694c9d4d01a72fafbdc9012675d1");

        assertThat(ScheduledEventService.coverUrlOf(raw, "1385996249957662770"))
                .isEqualTo("https://cdn.discordapp.com/guild-events/1385996249957662770"
                        + "/8210694c9d4d01a72fafbdc9012675d1.png");
        // An "a_" hash is animated and served as .gif, JDA's own rule. This tool can never write
        // one, but a hash it did not write would otherwise produce a URL that 404s.
        assertThat(ScheduledEventService.coverUrlOf(
                DataObject.empty().put("image", "a_8210694c9d4d01a72fafbdc9012675d1"), "1"))
                .endsWith(".gif");
    }

    @Test
    void theCoverSummaryCountsOnlyTheEventsTheLiveReadDescribed() {
        // The denominator is described events, not listed ones. Events come from JDA's cache and
        // covers from a live REST read, so "2 of 5 have no cover" would imply three URLs follow
        // when only one does.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(3, 2, 0, 2, 0, unlistedIds(0), 0, 0), true))
                .isEqualTo("\n(2 of 3 events have no cover image; 2 events were not in the live"
                        + " read, so their covers and schedules are unknown.)");
    }

    @Test
    void anEventMissingFromTheLiveReadIsSaidSoEvenWhenEveryOtherCoverIsPresent() {
        // A caveat gated on coverless events alone goes empty here, and an undescribed event then
        // renders with no cover line and no explanation — exactly "this event has no cover", the
        // claim the described set exists to avoid.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(3, 0, 0, 1, 0, unlistedIds(0), 0, 0), true))
                .isEqualTo("\n(1 event was not in the live read, so its cover and schedule are unknown.)");
    }

    @Test
    void anEventDiscordReturnedButTheCacheLacksIsSaidToBeMissingFromTheList() {
        // The stronger skew: such an event has no row at all, so there is nowhere to hang a
        // per-event caveat and the list would otherwise read as complete.
        //
        // The fact, not a cause. A lagging cache is the likeliest explanation and not the only
        // one, and naming it sends the reader to wait for something that may never arrive.
        // Named, which no other clause does: these events have no row below carrying their id, so
        // a bare count tells the reader something is missing and gives them no way to reach it.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(3, 0, 0, 0, 0, unlistedIds(2), 0, 0), true))
                .isEqualTo("\n(Discord returned 2 events not in this list (IDs: 900000000000000000,"
                        + " 900000000000000001), so the list is incomplete.)");
    }

    @Test
    void aLongListOfUnlistedIdsIsCutWithTheRemainderStated() {
        // The listing has no result cap, so this could run to a hundred snowflakes in a header
        // meant to be read at a glance. Ten is enough to act on — and the rest is counted rather
        // than dropped, because a silently truncated list reads as the whole of it.
        String caveat = ScheduledEventService.coverCaveat(
                new CoverCounts(0, 0, 0, 0, 0, unlistedIds(13), 0, 0), true);

        assertThat(caveat)
                .contains("Discord returned 13 events not in this list")
                .contains("900000000000000009")
                .contains(", and 3 more)")
                .doesNotContain("900000000000000010");
    }

    @Test
    void anEntryReturnedButUnreadableIsNotCalledAbsent() {
        // The distinction this set of counters exists for. Discord DID return the event; its
        // details would not parse. Reporting that as "not in the live read" describes a cache lag
        // that did not happen, and sends whoever reads it to look in the wrong place.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(2, 0, 1, 0, 0, unlistedIds(0), 0, 0), true))
                .isEqualTo("\n(1 event was returned but could not be read, so its cover is unknown.)");
        // Both at once, each named as itself.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(2, 1, 1, 1, 0, unlistedIds(0), 0, 0), true))
                // "1 of 2 events HAS": the noun counts the described events, the verb agrees with
                // the coverless one. Taking both from the same number gets one of them wrong.
                .isEqualTo("\n(1 of 2 events has no cover image; 1 event was returned but could"
                        + " not be read, so its cover is unknown; 1 event was not in the live read,"
                        + " so its cover and schedule are unknown.)");
    }

    @Test
    void anEntryWithNoUsableIdIsCountedOnItsOwn() {
        // It cannot be matched to a listed event in either direction, so it can be neither
        // "unreadable" (which names an event) nor folded into "not in the live read" (which would
        // blame the cache for a malformed response).
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(2, 0, 0, 0, 0, unlistedIds(0), 1, 0), true))
                .isEqualTo("\n(1 entry could not be read at all, so it is not counted against"
                        + " any event.)");
    }

    @Test
    void anUnreadableRecurrenceIsExplainedRatherThanShownAsNoSchedule() {
        // A malformed recurrence_rule beside a readable image: the event lists, its cover counts,
        // and it renders with no "Recurs:" line. Without this clause that reads as "does not
        // recur" — the silent claim the listing's outer catch already refuses to make.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(2, 0, 0, 0, 0, unlistedIds(0), 0, 1), true))
                .isEqualTo("\n(1 event's recurrence could not be read, so it shows no schedule"
                        + " below even if it recurs.)");
    }

    @Test
    void anUnreadableCoverDoesNotAlsoSuppressTheRecurrence() {
        // The mirror of the test above: recurrence read fine, cover did not. The event is
        // counted as unreadable-for-covers and
        // nothing claims anything about its schedule — in particular `recurrenceUnreadable` stays
        // 0, because the recurrence was read. Only its cover is unknown.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(2, 0, 1, 0, 0, unlistedIds(0), 0, 0), true))
                .isEqualTo("\n(1 event was returned but could not be read, so its cover is unknown.)")
                .doesNotContain("recurrence");
    }

    @Test
    void theTwoUnidentifiableClausesDoNotContradictEachOther() {
        // Together the pair has to read as one account: the absent clause says an id-less entry
        // may be among those events, and the unidentifiable clause says which tallies exclude it.
        // "Not counted either way" would deny the first; the wording asserted below says the same
        // thing without contradicting it. Asserted together, because each clause is defensible on
        // its own and it is the pair that has to be read.
        assertThat(ScheduledEventService.coverCaveat(
                new CoverCounts(1, 0, 0, 1, 0, unlistedIds(0), 1, 0), true))
                .contains("may include it")
                .contains("not counted against any event")
                .doesNotContain("either of those");
    }

    @Test
    void theTerminalClauseIsHedgedTooWhenNothingCanBeMatched() {
        // Same argument as the absent clause: an entry that came back with no usable id might be
        // this very event, so "Discord no longer returns it" stops being knowable once one exists.
        assertThat(ScheduledEventService.coverCaveat(
                new CoverCounts(1, 0, 0, 0, 1, unlistedIds(0), 1, 0), true))
                .contains("nothing in the live read matched")
                .doesNotContain("so Discord no longer returns");
        // With nothing unidentifiable, the flat claim is supported and stays.
        assertThat(ScheduledEventService.coverCaveat(
                new CoverCounts(1, 0, 0, 0, 1, unlistedIds(0), 0, 0), true))
                .contains("so Discord no longer returns it");
    }

    @Test
    void anAbsentEventIsHedgedWhenAnEntryCameBackUnidentifiable() {
        // The unidentifiable entry could be the absent event. Nothing matches them up, so the
        // flat claim stops being knowable — the one place the counter split cannot help, and so
        // the one place the wording has to.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(1, 0, 0, 1, 0, unlistedIds(0), 1, 0), true))
                .contains("not matched to anything in the live read")
                .contains("the entry with no id may include it")
                .doesNotContain("not in the live read, so");
        // With nothing unidentifiable, the flat claim is supported and stays.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(1, 0, 0, 1, 0, unlistedIds(0), 0, 0), true))
                .contains("1 event was not in the live read");
    }

    @Test
    void theHedgeAgreesWithItselfWhateverTheTwoCountsAre() {
        // Both counts vary independently, so all four shapes are reachable, and the noun and the
        // pronoun used to be keyed off different ones: two id-less entries beside one absent event
        // rendered "the entries with no id may be that one". Every other combination read fine,
        // which is exactly why it survived — both existing hedge tests fix unidentifiable at 1.
        assertThat(ScheduledEventService.coverCaveat(
                new CoverCounts(1, 0, 0, 1, 0, unlistedIds(0), 2, 0), true))
                .contains("the entries with no id may include it")
                .doesNotContain("may be that one");
        assertThat(ScheduledEventService.coverCaveat(
                new CoverCounts(1, 0, 0, 2, 0, unlistedIds(0), 1, 0), true))
                .contains("the entry with no id may be among them");
        assertThat(ScheduledEventService.coverCaveat(
                new CoverCounts(1, 0, 0, 2, 0, unlistedIds(0), 2, 0), true))
                .contains("the entries with no id may be among them");
    }

    @Test
    void theAbsoluteNoCoversPhrasingIsOnlyUsedWhenTheReadSawEverything() {
        // "no event here has a cover image" is a claim about every listed event, so it needs a
        // live read that described every listed event. With one described-and-coverless beside
        // two absent, it asserted something about two events it never saw — in the same sentence
        // that then called those two unknown.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(1, 1, 0, 2, 0, unlistedIds(0), 0, 0), true))
                // "1 of 1 event", singular on both. Reachable precisely because the shorthand
                // above also requires nothing unreadable, absent or unidentifiable.
                .isEqualTo("\n(1 of 1 event has no cover image; 2 events were not in the live"
                        + " read, so their covers and schedules are unknown.)")
                .doesNotContain("no event here");
        // Nothing missing, so the claim is supported and the shorthand stands.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(3, 3, 0, 0, 0, unlistedIds(0), 0, 0), true))
                .isEqualTo("\n(no event here has a cover image.)");
    }

    @Test
    void anEndedOrCancelledEventExplainsItsMissingCoverRatherThanShowingNone() {
        // Discord stops returning an event once it is over or cancelled, so its cover cannot be
        // read from the live listing. "Ended or been cancelled" because a cancelled event may
        // never have started — calling it finished contradicts its own Status: CANCELED row.
        // Counting it as "not in the live read" blames a cache gap for an event simply being
        // finished; dropping it entirely leaves a row with no cover URL and nothing to say why,
        // which reads as "this event has no cover". Its own clause says what is true.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(2, 0, 0, 0, 1, unlistedIds(0), 0, 0), true))
                // Cover and schedule in one clause: neither was read, both are missing from the
                // row, and for the same reason. A separate recurrence clause about the same event
                // said "could not be read", which reads as a parse failure rather than as the
                // event never being returned — on every guild that has ever run an event.
                .isEqualTo("\n(1 event has ended or been cancelled, so Discord no longer returns it,"
                        + " and no cover or schedule is shown below for it.)");
        // And it suppresses the all-coverless shorthand, which would otherwise claim over an
        // event whose cover was never read.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(2, 2, 0, 0, 1, unlistedIds(0), 0, 0), true))
                .doesNotContain("no event here")
                .contains("2 of 2 events have no cover image")
                .contains("ended or been cancelled");
    }

    @Test
    void aFullyDescribedListingWithEveryCoverPresentSaysNothing() {
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(3, 0, 0, 0, 0, unlistedIds(0), 0, 0), true)).isEmpty();
    }

    @Test
    void theOrdinaryCaseOfNoCoversAtAllSkipsTheArithmetic() {
        // Covers are rare, so "3 of 3 events have no cover image" is what most listings would
        // carry, and the numbers in it say nothing a reader can use.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(3, 3, 0, 0, 0, unlistedIds(0), 0, 0), true))
                .isEqualTo("\n(no event here has a cover image.)");
    }

    @Test
    void aFailedLiveReadCaveatsEverythingRatherThanCounting() {
        // Counts drawn from a read that did not happen are all zero, which would render as "every
        // event has a cover" — the failure mode the caveat exists for.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(0, 0, 0, 0, 0, unlistedIds(0), 0, 0), false))
                .contains("could not be read")
                .contains("as having a cover even if it is");
    }

    @Test
    void anEventWithNoCoverIsDistinguishableFromOneWithAnUnreadableCover() {
        // null here becomes "no cover image", which is a claim about the event. The caller turns a
        // failed read into different wording, because an absent cover is information and a failed
        // read is not.
        assertThat(ScheduledEventService.coverUrlOf(DataObject.empty(), "1")).isNull();
        assertThat(ScheduledEventService.coverUrlOf(DataObject.empty().putNull("image"), "1")).isNull();
        // And a third state: present, but not a string. getString would coerce it via toString and
        // build a URL around the result, reporting a cover this could not read. Throwing puts it
        // where the caller already handles an unreadable field.
        assertThatThrownBy(() -> ScheduledEventService.coverUrlOf(
                DataObject.empty().put("image", DataObject.empty()), "1"))
                .isInstanceOf(ParsingException.class);
        // And a fourth: a string, but not a hash. Building a URL from it gives .../1/.png, which
        // resolves to nothing and would be printed as this event's cover.
        assertThatThrownBy(() -> ScheduledEventService.coverUrlOf(
                DataObject.empty().put("image", ""), "1"))
                .isInstanceOf(ParsingException.class);
        // A fifth, and the reason the id beside it gets isSnowflake: this value is printed into a
        // URL. A slash or a newline in it does not make a broken hash, it makes a different URL.
        assertThatThrownBy(() -> ScheduledEventService.coverUrlOf(
                DataObject.empty().put("image", "aaa/../../evil"), "1"))
                .isInstanceOf(ParsingException.class);
        assertThatThrownBy(() -> ScheduledEventService.coverUrlOf(
                DataObject.empty().put("image", "aaa bbb"), "1"))
                .isInstanceOf(ParsingException.class);
        // The animated prefix stays legal: it is the one format variation Discord already uses.
        assertThat(ScheduledEventService.coverUrlOf(
                DataObject.empty().put("image", "a_8210694c9d4d01a72fafbdc9012675d1"), "1"))
                .endsWith(".gif");
    }

    private static final String COVER_URL = "https://cdn.discordapp.com/guild-events/11/aaa.png";

    @Test
    void onlyAResponseThatCarriedTheCoverSaysItWasSet() {
        // The headline and the "Now:" line answer the same question, and only one arrangement of
        // them is honest in each case. An unconditional "Set the cover image on X" would sit
        // above "Now: no cover image" — a confirmation and a contradiction in one message.
        assertThat(ScheduledEventService.describeCoverWrite("Community Night", "11", "poster.png",
                Icon.IconType.PNG, 2048, none(), present(COVER_URL)))
                .startsWith("Set the cover image on Community Night (ID: 11)")
                .contains("• Was: no cover image")
                .contains("• Now: " + COVER_URL);

        // Accepted, and the event came back with no cover. The write is not disputed; what it
        // achieved is, so the sentence stops short of claiming the cover is in place.
        assertThat(ScheduledEventService.describeCoverWrite("Community Night", "11", "poster.png",
                Icon.IconType.PNG, 2048, none(), none()))
                .startsWith("Sent the cover image to Community Night (ID: 11)")
                .contains("• Now: no cover image — check the event")
                .doesNotContain("Set the cover image");

        // The response would not parse. That establishes nothing at all — least of all that there
        // is no cover, which is the claim the ABSENT wording above makes.
        assertThat(ScheduledEventService.describeCoverWrite("Community Night", "11", "poster.png",
                Icon.IconType.PNG, 2048, present(COVER_URL), unknown()))
                .startsWith("Sent the cover image to")
                .contains("• Was: " + COVER_URL)
                .contains("• Now: unknown")
                .doesNotContain("no cover image —");
    }

    @Test
    void anUnreadablePreviousCoverDoesNotBecomeNoCoverImage() {
        // The pre-write read establishes that the event exists and captures what it had. A cover
        // field that will not parse costs the second job only — the write still goes ahead, and
        // the line that would have reported it says what happened instead of inventing a value.
        assertThat(ScheduledEventService.describeCoverWrite("Community Night", "11", "poster.png",
                Icon.IconType.PNG, 2048, unknown(), present(COVER_URL)))
                .startsWith("Set the cover image on")
                .contains("• Was: unknown — the event's previous cover could not be read")
                .contains("• Now: " + COVER_URL)
                // Nothing to compare against, so the unchanged note cannot fire.
                .doesNotContain("Unchanged");
    }

    @Test
    void anEventThatIsOverIsNamedAsSuchRatherThanLeftToTheGenericFailure() {
        // The one candidate cause the pre-write read can actually see. A size rejection, a missing
        // MANAGE_EVENTS and a finished event all arrive as the same exception, so the failure
        // message names none of them — but the status came back in the read a moment earlier, so
        // that much is observed rather than guessed.
        assertThat(ScheduledEventService.terminalStateOf(
                DataObject.empty().put("status", 3))).isEqualTo("COMPLETED");
        assertThat(ScheduledEventService.terminalStateOf(
                DataObject.empty().put("status", 4))).isEqualTo("CANCELED");
        // Live events say nothing: a cover on a running or upcoming event is ordinary.
        assertThat(ScheduledEventService.terminalStateOf(
                DataObject.empty().put("status", 1))).isNull();
        assertThat(ScheduledEventService.terminalStateOf(
                DataObject.empty().put("status", 2))).isNull();
        // Absent or unreadable costs a sentence of context, never the write.
        assertThat(ScheduledEventService.terminalStateOf(DataObject.empty())).isNull();
        assertThat(ScheduledEventService.terminalStateOf(
                DataObject.empty().put("status", "over"))).isNull();
        assertThat(ScheduledEventService.terminalStateOf(
                DataObject.empty().put("status", DataObject.empty()))).isNull();
    }

    @Test
    void aCoverHashThatDidNotMoveIsCalledOut() {
        // Uploading the file that was already there is the likeliest cause, and the message would
        // otherwise read as a successful change. Only reachable when the response confirmed the
        // hash — the other two states have no "after" to compare.
        assertThat(ScheduledEventService.describeCoverWrite("Community Night", "11", "poster.png",
                Icon.IconType.PNG, 2048, present(COVER_URL), present(COVER_URL)))
                .contains("• Unchanged: the event's cover hash did not move");
        assertThat(ScheduledEventService.describeCoverWrite("Community Night", "11", "poster.png",
                Icon.IconType.PNG, 2048, none(), present(COVER_URL)))
                .doesNotContain("Unchanged");
    }

    @Test
    void eachEventGetsItsOwnCoverAndItsOwnSchedule() {
        // The last hop of the live read, and the one thing the parse and count tests cannot reach:
        // both build their input by hand, so a line keyed off the wrong id prints one event's
        // cover under another's name with every other test still green. Two events with a cover
        // and a rule apiece, crossed over, so a swapped map or a shared key fails here.
        ScheduledEvent night = liveEvent("11", "Community Night");
        ScheduledEvent chess = liveEvent("22", "Chess Club");
        Map<String, String> rules = Map.of("22", "Weekly on Monday");
        Map<String, String> covers = Map.of(
                "11", "https://cdn.discordapp.com/guild-events/11/aaa.png");

        assertThat(ScheduledEventService.renderEvent(night, rules, covers, Set.of(), Set.of(), false))
                .contains("Community Night")
                .contains("• Cover image: https://cdn.discordapp.com/guild-events/11/aaa.png")
                .doesNotContain("Recurs:")
                .doesNotContain("Interested:");
        assertThat(ScheduledEventService.renderEvent(chess, rules, covers, Set.of(), Set.of(), true))
                .contains("Chess Club")
                .contains("• Recurs: Weekly on Monday")
                .doesNotContain("Cover image")
                .contains("• Interested: 7 users");
    }

    @Test
    void anEventWithNeitherPrintsNeitherLine() {
        // A per-event "no cover image" would be a line of nothing on every coverless event in a
        // listing with no result cap; the header caveat carries that fact once instead.
        assertThat(ScheduledEventService.renderEvent(
                liveEvent("11", "Community Night"), Map.of(), Map.of(), Set.of(), Set.of(), false))
                .doesNotContain("Cover image")
                .doesNotContain("Recurs:");
    }

    @Test
    void anEventWhoseCoverCouldNotBeReadDoesNotLookLikeOneWithoutACover() {
        // Nine coverless events beside one whose image field would not parse rendered ten
        // identical rows: the header counted them separately and no row said which was which,
        // on exactly the question a caller answers when it picks an event to upload a cover to.
        ScheduledEvent unreadable = liveEvent("11", "Community Night");
        ScheduledEvent coverless = liveEvent("22", "Chess Club");

        assertThat(ScheduledEventService.renderEvent(
                unreadable, Map.of(), Map.of(), Set.of("11"), Set.of(), false))
                .contains("• Cover image: unknown")
                .contains("the live read did not describe this event");
        assertThat(ScheduledEventService.renderEvent(
                coverless, Map.of(), Map.of(), Set.of("11"), Set.of(), false))
                .doesNotContain("Cover image");
        // A readable cover wins: the marker is for rows that would otherwise say nothing.
        assertThat(ScheduledEventService.renderEvent(unreadable, Map.of(),
                Map.of("11", "https://cdn.discordapp.com/guild-events/11/aaa.png"),
                Set.of("11"), Set.of(), false))
                .contains("• Cover image: https://cdn.discordapp.com/guild-events/11/aaa.png")
                .doesNotContain("unknown");
    }

    @Test
    void anUnreadableRecurrenceDoesNotLookLikeAOneOff() {
        // The same loss on the other field, and the one this listing's recurrence read exists to
        // prevent: a weekly series whose rule would not parse renders exactly like a one-off, and
        // gets edited as though it were one. The caveat counts it; only the row can name it.
        ScheduledEvent unknown = liveEvent("11", "Community Night");
        ScheduledEvent oneOff = liveEvent("22", "Chess Club");

        assertThat(ScheduledEventService.renderEvent(
                unknown, Map.of(), Map.of(), Set.of(), Set.of("11"), false))
                .contains("• Recurs: could not be read")
                .contains("may be a series");
        assertThat(ScheduledEventService.renderEvent(
                oneOff, Map.of(), Map.of(), Set.of(), Set.of("11"), false))
                .doesNotContain("Recurs:");
        // A rule that was read wins: the marker is for rows that would otherwise say nothing.
        assertThat(ScheduledEventService.renderEvent(
                unknown, Map.of("11", "Weekly on Monday"), Map.of(), Set.of(), Set.of("11"), false))
                .contains("• Recurs: Weekly on Monday")
                .doesNotContain("could not be read");
    }

    private static ScheduledEvent liveEvent(String id, String name) {
        ScheduledEvent e = mock(ScheduledEvent.class);
        lenient().when(e.getId()).thenReturn(id);
        lenient().when(e.getName()).thenReturn(name);
        lenient().when(e.getStartTime()).thenReturn(OffsetDateTime.parse("2026-08-05T20:00:00Z"));
        lenient().when(e.getInterestedUserCount()).thenReturn(7);
        return e;
    }

    @Test
    void aCoverImageIsIdentifiedFromItsBytesNotItsName() {
        // The name is caller-supplied text. Trusting it means building a PNG icon around a JPEG
        // body, which Discord rejects with an error that blames the request rather than the file.
        assertThat(ScheduledEventService.coverType(png(), "filePath")).isEqualTo(Icon.IconType.PNG);
        assertThat(ScheduledEventService.coverType(jpeg(), "filePath")).isEqualTo(Icon.IconType.JPEG);
    }

    @Test
    void aGifCoverIsRefusedByName() {
        // Discord animates avatars and banners but not event covers, so this is the plausible
        // mistake rather than an exotic one, and the message has to say which. It states the
        // product fact — the animation is never shown — rather than claiming Discord rejects
        // GIFs, which is more than is known: a static GIF would presumably store fine.
        assertThatThrownBy(() -> ScheduledEventService.coverType("GIF89a".getBytes(StandardCharsets.US_ASCII), "filePath"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A GIF's animation is never shown");
    }

    @Test
    void anythingElseIsRefusedBeforeItReachesDiscord() {
        assertThatThrownBy(() -> ScheduledEventService.coverType("<svg/>".getBytes(StandardCharsets.US_ASCII), "imageUrl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("imageUrl is not a PNG or JPEG");
        // A truncated body must not index past its end. Every prefix of a real PNG is a plausible
        // partial download.
        assertThatThrownBy(() -> ScheduledEventService.coverType(new byte[]{(byte) 0x89, 'P'}, "filePath"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduledEventService.coverType(new byte[0], "filePath"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aValidLocalCoverIsReadBeforeTheEventAndChangesNothingWhenThatReadFails(@TempDir Path dir)
            throws IOException {
        // The source is read first — deliberately, so a wrong one is refused without spending a
        // Discord request. An economy, not a defence: both guards refuse wherever they run. A
        // file inside the
        // root therefore passes the guard and is read, and the call then stops at the event read,
        // which the mocked JDA cannot serve — RestActionImpl casts its JDA argument to JDAImpl and
        // a Mockito proxy is not one. So the exact string pinned below is an artifact of the mock,
        // not something production produces; what the test establishes is the ordering in its
        // name. If a JDA upgrade validates earlier, this fails loudly rather than passing
        // wrongly. What is asserted is the message, not the absence of a write — nothing here
        // could observe a PATCH, since the same mock that fails the read would fail it too.
        Path root = Files.createDirectory(dir.resolve("uploads"));
        Files.write(root.resolve("poster.png"), png());
        service.coverFileRoot = root.toString();

        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, EVENT, null, root.resolve("poster.png").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Could not reach Discord to read that event")
                .hasMessageContaining("Nothing was changed");
    }

    @Test
    void aWebpFromACdnLinkIsRefusedByTheParameterItCameFrom() {
        // Discord's own media proxy serves WebP, so this is an ordinary mistake rather than an
        // exotic one — and the refusal has to name imageUrl, not the filePath the caller left
        // empty. Only reachable through the tool, since coverType alone cannot pick the name.
        service.coverFileRoot = "";
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

        try (MockedStatic<RemoteFetchGuard> guard = mockStatic(RemoteFetchGuard.class)) {
            guard.when(() -> RemoteFetchGuard.fetch(any(), anyInt(), any())).thenReturn(webp);

            assertThatThrownBy(() -> service.setScheduledEventImage(
                    GUILD, EVENT, "https://media.discordapp.net/x.webp", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("imageUrl is not a PNG or JPEG");
        }
    }

    @Test
    void anOversizedUrlReportsTheSameLimitAndAdviceAsAnOversizedFile() {
        // The multi-catch exists so both guards' size refusals read the same way. RemoteFetchGuard
        // says only "exceeds the maximum allowed size" — no limit, no capital, no period — so
        // without this the two branches diverged for the same mistake. The file branch's half of
        // the comparison is anOversizedCoverIsRefusedWithAdviceRatherThanJustALimit; this asserts
        // the URL branch produces the same two strings, which is the claim the name now makes.
        service.coverFileRoot = "";

        try (MockedStatic<RemoteFetchGuard> guard = mockStatic(RemoteFetchGuard.class)) {
            guard.when(() -> RemoteFetchGuard.fetch(any(), anyInt(), any())).thenThrow(
                    new RemoteFetchGuard.TooLargeException("cover image exceeds the maximum allowed size"));

            assertThatThrownBy(() -> service.setScheduledEventImage(
                    GUILD, EVENT, "https://cdn.discordapp.com/big.png", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cover image exceeds the 5 MB limit.")
                    .hasMessageContaining("Crop it to 5:2");
        }
    }

    @Test
    void anEmptyCacheWithAnUnreadableLiveListDoesNotClaimThereAreNone() {
        // "No scheduled events found" is a claim, and an empty cache alone does not support it —
        // an event created out of band exists at Discord before the gateway delivers it here. The
        // mocked JDA cannot complete the live list, so nothing confirms it either way, and the
        // property is that a failed confirmation is never reported as an answer.
        when(guild.getScheduledEvents()).thenReturn(java.util.List.of());

        // The failed live read degrades the same way it does with a warm cache — one policy, not
        // two — and "no scheduled events" is withheld because nothing established it.
        String result = service.listScheduledEvents(GUILD, "false");

        assertThat(result)
                .contains("could not be read")
                .doesNotContain("No scheduled events found")
                // No rows, so no separator: the caveat used to sit above a blank line, which
                // reads as an answer that got cut off rather than one with nothing to list.
                .doesNotEndWith("\n");
    }

    @Test
    void aCoverIsNotReadOutOfTheDirectoryDownloadsAreWrittenInto(@TempDir Path dir)
            throws IOException {
        // The README's case for reusing DISCORD_MCP_FILE_ROOT rests on the magic-byte check, and
        // that argument is void when the upload root is also where download_attachment writes: a
        // caller fetches a file it chose, with a PNG header it chose, and this pins it to a
        // permanent unauthenticated URL. Two independently configured values, so unlike a check
        // that compares a root against the literal it was built from, this one can fire.
        Path shared = Files.createDirectory(dir.resolve("shared"));
        Files.write(shared.resolve("poster.png"), png());
        service.coverFileRoot = shared.toString();
        service.downloadRoot = shared.toString();

        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, EVENT, null, shared.resolve("poster.png").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISCORD_MCP_FILE_ROOT and DISCORD_MCP_DOWNLOAD_ROOT overlap");

        // Nesting is the same arrangement: files written under the downloads directory are inside
        // the upload root, so they are readable by path just as surely as if the two were equal.
        Path uploads = Files.createDirectory(dir.resolve("uploads"));
        service.coverFileRoot = uploads.toString();
        service.downloadRoot = Files.createDirectory(uploads.resolve("downloads")).toString();
        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, EVENT, null, uploads.resolve("poster.png").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");

        // And imageUrl is untouched by any of it — it reads no files, so the collision cannot
        // apply to it. This must not become a refusal that disables the whole tool.
        service.coverFileRoot = shared.toString();
        service.downloadRoot = shared.toString();
        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, EVENT, "https://169.254.169.254/x.png", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disallowed (internal) address");
    }

    @Test
    void anUnsetDownloadRootCollidesWithNothing() throws IOException {
        // The deployment the README recommends: no downloads configured, uploads under the
        // service's working directory. Spring hands an unset variable "" rather than null, and
        // Paths.get("") resolves to the working directory — so the overlap check compared the
        // upload root against the process CWD and refused every filePath cover on that layout,
        // naming a variable the operator never set.
        //
        // Created under target/ so it is genuinely below the working directory, which is the
        // whole point; a @TempDir lives under the system tmpdir and cannot reproduce this.
        // createDirectories first: under Surefire the working directory is the module root and
        // target/ is already there, but an IDE runner can start elsewhere, and then the parent
        // this needs does not exist. The directory must be below the CWD either way — that is
        // what reproduces the bug.
        Path parent = Files.createDirectories(Path.of("target").toAbsolutePath());
        Path underCwd = Files.createTempDirectory(parent, "cover-root");
        try {
            Files.write(underCwd.resolve("poster.png"), png());
            service.coverFileRoot = underCwd.toString();
            service.downloadRoot = "";

            assertThatThrownBy(() -> service.setScheduledEventImage(
                    GUILD, EVENT, null, underCwd.resolve("poster.png").toString()))
                    .isInstanceOf(IllegalArgumentException.class)
                    // Past the roots and the read, stopped at the event the mock cannot serve.
                    .hasMessageContaining("Could not reach Discord")
                    .hasMessageNotContaining("overlap");
        } finally {
            Files.deleteIfExists(underCwd.resolve("poster.png"));
            Files.deleteIfExists(underCwd);
        }
    }

    @Test
    void aDownloadRootThatDoesNotResolveDisablesTheCheckRatherThanTheTool(@TempDir Path dir)
            throws IOException {
        // The branch that fails open, so the one worth pinning. A download root pointing at a
        // directory that is not there means nothing is being written there, so there is nothing
        // to collide with — and a broken download root is download_attachment's to report, with
        // the parameter names its own caller used, not this tool's to refuse a cover over.
        Path uploads = Files.createDirectory(dir.resolve("uploads"));
        Files.write(uploads.resolve("poster.png"), png());
        service.coverFileRoot = uploads.toString();
        service.downloadRoot = dir.resolve("not-created").toString();

        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, EVENT, null, uploads.resolve("poster.png").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Could not reach Discord")
                .hasMessageNotContaining("overlap")
                .hasMessageNotContaining("DISCORD_MCP_DOWNLOAD_ROOT");
    }

    @Test
    void separateRootsAreNotRefused(@TempDir Path dir) throws IOException {
        // The other half of the check: an ordinary two-directory deployment must reach the file.
        // Without this, a comparison that refused everything would pass the test above.
        Path uploads = Files.createDirectory(dir.resolve("uploads"));
        Files.write(uploads.resolve("poster.png"), png());
        service.coverFileRoot = uploads.toString();
        service.downloadRoot = Files.createDirectory(dir.resolve("downloads")).toString();

        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, EVENT, null, uploads.resolve("poster.png").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                // Past the roots, past the read, stopped at the event — which the mocked JDA
                // cannot serve. Not "overlap", and not a refusal about the file.
                .hasMessageContaining("Could not reach Discord")
                .hasMessageNotContaining("overlap");
    }

    @Test
    void aFailedLiveReadDoesNotMarkEveryRowUnknown() {
        // The per-row marker earns its line by being rare. When the live read fails nothing is
        // described, so every non-terminal event qualifies and an uncapped listing gets the
        // marker on every row — under a caveat that has just said no cover could be read at all.
        // The counts already go to none() on this branch; the marker set has to as well.
        // Built before the stubbing, not inside it: liveEvent stubs its own mock, and Mockito
        // reads a stubbing opened inside another stubbing's arguments as unfinished.
        ScheduledEvent night = liveEvent("11", "Community Night");
        ScheduledEvent chess = liveEvent("22", "Chess Club");
        when(guild.getScheduledEvents()).thenReturn(java.util.List.of(night, chess));

        String result = service.listScheduledEvents(GUILD, "false");

        assertThat(result)
                .contains("Community Night")
                .contains("Chess Club")
                .contains("could not be read")
                .doesNotContain("Cover image");
    }

    @Test
    void theCoverWriteDoesNotConsultTheCacheAtAll() {
        // The write goes through patchRaw, so an event Discord has but the gateway has not
        // delivered is coverable — which is the flow the tool description recommends: create an
        // event, then cover it from the poster's CDN link. Stubbing the cache to miss changes
        // nothing, because nothing reads it.
        when(guild.getScheduledEventById(EVENT)).thenReturn(null);
        service.coverFileRoot = "";

        try (MockedStatic<RemoteFetchGuard> guard = mockStatic(RemoteFetchGuard.class)) {
            guard.when(() -> RemoteFetchGuard.fetch(any(), anyInt(), any())).thenReturn(png());

            assertThatThrownBy(() -> service.setScheduledEventImage(
                    GUILD, EVENT, "https://cdn.discordapp.com/x.png", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    // Past the source, into the live read — which the mocked JDA cannot serve.
                    // The point is that an empty cache did not stop it before that.
                    .hasMessageContaining("Could not reach Discord to read that event")
                    .hasMessageContaining("Nothing was changed")
                    .hasMessageNotContaining("not found by eventId");
        }
    }

    @Test
    void aWhitespaceOnlyArgumentIsNotASuppliedOne() {
        // isEmpty would treat "   " as supplied and fail later with "File not found at filePath:
        // ", naming a parameter the caller did fill in with nothing.
        assertThatThrownBy(() -> service.setScheduledEventImage(GUILD, EVENT, "  ", "	"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Supply either imageUrl");
    }

    @Test
    void anEventIdThatIsNotASnowflakeNeverReachesARoute() {
        // Every cache-based tool inherits this from MiscUtil.parseSnowflake inside
        // getScheduledEventById. This one skips the cache deliberately, and Route#compile
        // substitutes the placeholder textually with no escaping — so a value with a slash or a
        // dot segment would choose which endpoint the bot token is spent on. OkHttp canonicalises
        // the dot segments for it.
        //
        // The URL below is a real one the guard has to stop rather than a shape argument: it
        // reaches an entirely different resource under the same token.
        service.coverFileRoot = "";
        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, "1385996249957662770/../../../users/@me", "https://example.com/x.png", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId must be a Discord snowflake");
        // Refused before the source is fetched, not after: the SSRF guard would otherwise have
        // rejected that URL first and this assertion would pass without the id ever being checked.
        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, "not-a-snowflake", "http://169.254.169.254/", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId must be a Discord snowflake");
        // Blank is not "null" either: a message naming a condition that did not fire sends the
        // caller to check the wrong thing.
        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, "   ", "https://example.com/x.png", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId must be a Discord snowflake");
        // Arabic-Indic "123", written as escapes so this cannot quietly become an ASCII string in
        // some other encoding and pass for the wrong reason. Character.isDigit is true for every
        // Unicode decimal digit, and so is Long.parseUnsignedLong, so both halves of the check
        // would let it reach the route. Not a traversal — the worst case is a 404 — but a check
        // whose name promises more than it delivers is the shape this file keeps getting wrong.
        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, "\u0661\u0662\u0663", "https://example.com/x.png", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId must be a Discord snowflake");
        // 20 digits is a legal snowflake length and this one still overflows 64 bits, so JDA's own
        // parse of it would throw rather than address the event the caller meant.
        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, "99999999999999999999", "https://example.com/x.png", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId must be a Discord snowflake");
    }

    @Test
    void exactlyOneOfImageUrlAndFilePathIsRequired() {
        // Neither: the message leads with imageUrl, because that is the option needing no
        // filesystem grant, and steering callers to it is what keeps deployments off the
        // shared-root configuration.
        assertThatThrownBy(() -> service.setScheduledEventImage(GUILD, EVENT, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Supply either imageUrl")
                .hasMessageContaining("no filesystem access needed");
        assertThatThrownBy(() -> service.setScheduledEventImage(GUILD, EVENT, "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Supply either imageUrl");
        // Both: ambiguous about which one was meant, so neither is guessed at.
        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, EVENT, "https://cdn.discordapp.com/x.png", "/tmp/x.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not both");
    }

    @Test
    void anImageUrlGoesThroughTheSharedSsrfGuard() {
        // The whole point of offering imageUrl is that it needs no filesystem grant — which makes
        // it the parameter an injected prompt would reach for. It must not become a second,
        // unguarded fetch path; that is the exact regression RemoteFetchGuard exists to prevent.
        //
        // Each assertion pins the GUARD's own wording rather than just the exception type.
        // `isInstanceOf` alone would be satisfied by any early throw — with a null guildId, by
        // "guildId cannot be null", even against a bare openStream(). A test that cannot fail for
        // the reason it names is worse than no test, so the guild and event resolve here and the
        // fetch is genuinely reached.
        service.coverFileRoot = "";

        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, EVENT, "http://169.254.169.254/latest/meta-data/", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use the https scheme");
        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, EVENT, "file:///etc/passwd", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use the https scheme");
        // https, so the scheme check passes and the address check is what has to stop it.
        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, EVENT, "https://169.254.169.254/latest/meta-data/", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disallowed (internal) address");
    }

    @Test
    void aLocalPathStillRequiresTheUploadRootButAUrlDoesNot() {
        // Unset FILE_ROOT must not disable the tool outright any more, only its filePath half,
        // and the refusal has to point at the option that still works.
        service.coverFileRoot = "";

        assertThatThrownBy(() -> service.setScheduledEventImage(GUILD, EVENT, null, "/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Local paths are disabled")
                .hasMessageContaining("supply imageUrl");
    }

    @Test
    void aFailedWriteReportsWhatTheEventActuallyHasAfterwards() {
        // Every branch is a claim about whether a write took effect. Asserting "not changed" on a
        // thrown request is what this replaced: a lost response after Discord applied the image is
        // indistinguishable from a rejection, so the read-back is the only honest answer.
        String was = "https://cdn.discordapp.com/guild-events/1/aaa.png";
        String now = "https://cdn.discordapp.com/guild-events/1/bbb.png";

        // A known removal is a bigger change than a swap and must not read as "never had one".
        assertThat(ScheduledEventService.describeOutcome(none(), present(was)))
                .contains("was REMOVED during this call")
                .contains(was);
        // Nothing to compare against: no removal can be claimed.
        assertThat(ScheduledEventService.describeOutcome(none(), none()))
                .contains("currently has no cover image");
        assertThat(ScheduledEventService.describeOutcome(present(was), present(was)))
                .contains("still has the cover it had before");
        assertThat(ScheduledEventService.describeOutcome(present(now), present(was)))
                .contains("CHANGED during this call")
                .contains(now)
                // Must NOT claim this request made the change: a genuine rejection followed by a
                // concurrent edit reaches the same branch, and nothing here can tell them apart.
                .contains("something else changed it in the meantime");
        // Added, not merely "changed": it had none and now has one. Knowable, and asymmetric the
        // other way from a removal.
        assertThat(ScheduledEventService.describeOutcome(present(now), none()))
                .contains("was ADDED during this call")
                .contains(now)
                .contains("something else set it in the meantime");
    }

    @Test
    void anUnreadableCoverOnEitherSideIsNotComparedAgainstAnything() {
        String was = "https://cdn.discordapp.com/guild-events/1/aaa.png";
        String now = "https://cdn.discordapp.com/guild-events/1/bbb.png";

        // The read-back reached the event and not its cover. REMOVED, CHANGED and "still has"
        // are all comparisons, and there is nothing here to compare — including the one that
        // would otherwise fire: `now == null` reads as a removal.
        assertThat(ScheduledEventService.describeOutcome(unknown(), present(was)))
                .contains("whether this call applied is unknown")
                .doesNotContain("REMOVED");
        // The other side: the write's own read-back is fine, but what was there before was never
        // established, so neither ADDED nor CHANGED can be claimed.
        assertThat(ScheduledEventService.describeOutcome(present(now), unknown()))
                .contains(now)
                .contains("could not be read")
                .doesNotContain("ADDED")
                .doesNotContain("CHANGED");
        assertThat(ScheduledEventService.describeOutcome(none(), unknown()))
                .contains("has no cover image now")
                .doesNotContain("REMOVED");
    }

    /** N unlisted ids: these cases are about the count, and the ids only have to be distinct. */
    private static java.util.List<String> unlistedIds(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> String.valueOf(900000000000000000L + i))
                .toList();
    }

    private static ScheduledEventService.Cover present(String url) {
        return new ScheduledEventService.Cover(url, ScheduledEventService.Cover.State.PRESENT);
    }

    private static ScheduledEventService.Cover none() {
        return new ScheduledEventService.Cover(null, ScheduledEventService.Cover.State.ABSENT);
    }

    private static ScheduledEventService.Cover unknown() {
        return new ScheduledEventService.Cover(null, ScheduledEventService.Cover.State.UNKNOWN);
    }

    @Test
    void settingACoverRefusesAPathOutsideTheUploadRoot(@TempDir Path dir) throws IOException {
        // Proves the guard is actually wired in, not merely imported. Without it this tool is a
        // read of any file the process can open, on a service that holds a bot token.
        Path root = Files.createDirectory(dir.resolve("uploads"));
        Path outside = Files.write(dir.resolve("secret.png"), png());
        service.coverFileRoot = root.toString();

        assertThatThrownBy(() -> service.setScheduledEventImage(GUILD, EVENT, null, outside.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a readable file inside the allowed upload directory");
    }

    @Test
    void anOversizedCoverIsRefusedWithAdviceRatherThanJustALimit(@TempDir Path dir) throws IOException {
        // The ordinary case, not an exotic one: a full-resolution master is usually both too big
        // and the wrong shape, so the limit on its own leaves the caller stuck.
        Path root = Files.createDirectory(dir.resolve("uploads"));
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(png(), 0, big, 0, png().length);
        Path file = Files.write(root.resolve("master.png"), big);
        service.coverFileRoot = root.toString();

        assertThatThrownBy(() -> service.setScheduledEventImage(GUILD, EVENT, null, file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cover image exceeds the 5 MB limit.")
                .hasMessageContaining("Crop it to 5:2");
    }

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    }

    private static byte[] jpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    }

    private DataObject event(String start, String end) {
        DataObject raw = DataObject.empty().put("scheduled_start_time", start);
        if (end != null) {
            raw.put("scheduled_end_time", end);
        }
        return raw;
    }
}
