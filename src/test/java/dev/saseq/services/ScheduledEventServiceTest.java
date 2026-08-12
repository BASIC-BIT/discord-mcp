package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.managers.ScheduledEventManager;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledEventServiceTest {

    private ScheduledEventService service;

    /**
     * A guild and event that resolve, so the source checks are actually reached.
     *
     * <p>setScheduledEventImage looks the event up before touching the source, deliberately: a
     * mistyped eventId should not cost a 5 MB transfer first. That means a test passing a null
     * guildId stops at "guildId cannot be null" and never exercises what it claims to — which is
     * how an assertion ends up passing for a reason it did not name.
     */
    private static final String GUILD = "480695542155051010";
    private static final String EVENT = "1385996249957662770";

    private ScheduledEventManager manager;
    private Guild guild;

    @BeforeEach
    void setUp() {
        JDA jda = mock(JDA.class);
        guild = mock(Guild.class);
        ScheduledEvent event = mock(ScheduledEvent.class);
        // lenient() is inert here — this class uses plain mock() with no MockitoExtension, so no
        // strictness is in effect to relax. Kept as a marker of which stubs are shared setup that
        // not every test uses, and so the calls stay correct if the extension is ever added.
        manager = mock(ScheduledEventManager.class);
        lenient().when(event.getManager()).thenReturn(manager);
        lenient().when(manager.setImage(any())).thenReturn(manager);
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
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(3, 2, 0, 2, 0, 0, 0, 0), true))
                .isEqualTo("\n(2 of 3 events have no cover image; 2 events were not in the live"
                        + " read, so their covers are unknown.)");
    }

    @Test
    void anEventMissingFromTheLiveReadIsSaidSoEvenWhenEveryOtherCoverIsPresent() {
        // The case that made the earlier version wrong: with no coverless events the caveat went
        // empty, so an undescribed event rendered with no cover line and no explanation — exactly
        // "this event has no cover", the claim the described set exists to avoid.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(3, 0, 0, 1, 0, 0, 0, 0), true))
                .isEqualTo("\n(1 event was not in the live read, so its cover is unknown.)");
    }

    @Test
    void anEventDiscordReturnedButTheCacheLacksIsSaidToBeMissingFromTheList() {
        // The stronger skew: such an event has no row at all, so there is nowhere to hang a
        // per-event caveat and the list would otherwise read as complete.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(3, 0, 0, 0, 0, 2, 0, 0), true))
                .isEqualTo("\n(Discord returned 2 events not in this list, so the list is"
                        + " incomplete — the cache has not caught up.)");
    }

    @Test
    void anEntryReturnedButUnreadableIsNotCalledAbsent() {
        // The distinction this set of counters exists for. Discord DID return the event; its
        // details would not parse. Reporting that as "not in the live read" describes a cache lag
        // that did not happen, and sends whoever reads it to look in the wrong place.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(2, 0, 1, 0, 0, 0, 0, 0), true))
                .isEqualTo("\n(1 event was returned but could not be read, so its cover is unknown.)");
        // Both at once, each named as itself.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(2, 1, 1, 1, 0, 0, 0, 0), true))
                // "1 of 2 events HAS": the noun counts the described events, the verb agrees with
                // the coverless one. Taking both from the same number gets one of them wrong.
                .isEqualTo("\n(1 of 2 events has no cover image; 1 event was returned but could"
                        + " not be read, so its cover is unknown; 1 event was not in the live read,"
                        + " so its cover is unknown.)");
    }

    @Test
    void anEntryWithNoUsableIdIsCountedOnItsOwn() {
        // It cannot be matched to a listed event in either direction, so it can be neither
        // "unreadable" (which names an event) nor folded into "not in the live read" (which would
        // blame the cache for a malformed response).
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(2, 0, 0, 0, 0, 0, 1, 0), true))
                .isEqualTo("\n(1 entry could not be read at all, so it is not counted above"
                        + " either way.)");
    }

    @Test
    void anUnreadableRecurrenceIsExplainedRatherThanShownAsNoSchedule() {
        // A malformed recurrence_rule beside a readable image: the event lists, its cover counts,
        // and it renders with no "Recurs:" line. Without this clause that reads as "does not
        // recur" — the silent claim the listing's outer catch already refuses to make.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(2, 0, 0, 0, 0, 0, 0, 1), true))
                .isEqualTo("\n(1 event's recurrence could not be read, so it shows no schedule"
                        + " below even if it recurs.)");
    }

    @Test
    void anUnreadableCoverDoesNotAlsoSuppressTheRecurrence() {
        // The mirror of the test above: recurrence read fine, cover did not. The event is
        // counted as unreadable-for-covers and
        // nothing claims anything about its schedule — in particular `recurrenceUnreadable` stays
        // 0, because the recurrence was read. Only its cover is unknown.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(2, 0, 1, 0, 0, 0, 0, 0), true))
                .isEqualTo("\n(1 event was returned but could not be read, so its cover is unknown.)")
                .doesNotContain("recurrence");
    }

    @Test
    void anAbsentEventIsHedgedWhenAnEntryCameBackUnidentifiable() {
        // The unidentifiable entry could be the absent event. Nothing matches them up, so the
        // flat claim stops being knowable — the one place the counter split cannot help, and so
        // the one place the wording has to.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(1, 0, 0, 1, 0, 0, 1, 0), true))
                .contains("not matched to anything in the live read")
                .contains("the entries with no id may be among them")
                .doesNotContain("not in the live read, so");
        // With nothing unidentifiable, the flat claim is supported and stays.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(1, 0, 0, 1, 0, 0, 0, 0), true))
                .contains("1 event was not in the live read");
    }

    @Test
    void theAbsoluteNoCoversPhrasingIsOnlyUsedWhenTheReadSawEverything() {
        // "no event here has a cover image" is a claim about every listed event, so it needs a
        // live read that described every listed event. With one described-and-coverless beside
        // two absent, it asserted something about two events it never saw — in the same sentence
        // that then called those two unknown.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(1, 1, 0, 2, 0, 0, 0, 0), true))
                // "1 of 1 event", singular on both. Reachable precisely because the shorthand
                // above also requires nothing unreadable, absent or unidentifiable.
                .isEqualTo("\n(1 of 1 event has no cover image; 2 events were not in the live"
                        + " read, so their covers are unknown.)")
                .doesNotContain("no event here");
        // Nothing missing, so the claim is supported and the shorthand stands.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(3, 3, 0, 0, 0, 0, 0, 0), true))
                .isEqualTo("\n(no event here has a cover image.)");
    }

    @Test
    void anEndedOrCancelledEventExplainsItsMissingCoverRatherThanShowingNone() {
        // Discord stops returning an event once it is over or cancelled, so its cover cannot be
        // read from the live listing. "Ended or been cancelled" because a cancelled event may
        // never have started — calling it finished contradicts its own Status: CANCELED row. Counting it as "not in the live read" blamed a cache gap for an event
        // simply being finished; dropping it entirely left a row with no cover URL and nothing to
        // say why, which reads as "this event has no cover". Its own clause says what is true.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(2, 0, 0, 0, 1, 0, 0, 0), true))
                .isEqualTo("\n(1 event has ended or been cancelled, so Discord no longer returns it and no"
                        + " cover is shown below for it.)");
        // And it suppresses the all-coverless shorthand, which would otherwise claim over an
        // event whose cover was never read.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(2, 2, 0, 0, 1, 0, 0, 0), true))
                .doesNotContain("no event here")
                .contains("2 of 2 events have no cover image")
                .contains("ended or been cancelled");
    }

    @Test
    void aFullyDescribedListingWithEveryCoverPresentSaysNothing() {
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(3, 0, 0, 0, 0, 0, 0, 0), true)).isEmpty();
    }

    @Test
    void theOrdinaryCaseOfNoCoversAtAllSkipsTheArithmetic() {
        // Covers are rare, so "3 of 3 events have no cover image" is what most listings would
        // carry, and the numbers in it say nothing a reader can use.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(3, 3, 0, 0, 0, 0, 0, 0), true))
                .isEqualTo("\n(no event here has a cover image.)");
    }

    @Test
    void aFailedLiveReadCaveatsEverythingRatherThanCounting() {
        // Counts drawn from a read that did not happen are all zero, which would render as "every
        // event has a cover" — the failure mode the caveat exists for.
        assertThat(ScheduledEventService.coverCaveat(new CoverCounts(0, 0, 0, 0, 0, 0, 0, 0), false))
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
    void aValidLocalCoverIsAcceptedAndHandedToDiscordAsTheRightFormat(@TempDir Path dir)
            throws IOException {
        // Everything from the guard to the upload had no coverage: that a file inside the root is
        // accepted at all, and that setImage is called with an Icon built from the sniffed type
        // rather than the extension. The read-back afterwards has no JDA behind it and throws,
        // which exercises the could-not-confirm path — the one that must not claim success it
        // cannot see.
        Path root = Files.createDirectory(dir.resolve("uploads"));
        Path file = Files.write(root.resolve("poster.png"), png());
        service.coverFileRoot = root.toString();

        String result = service.setScheduledEventImage(GUILD, EVENT, null, file.toString());

        verify(manager).setImage(any(Icon.class));
        assertThat(result)
                .contains("Set the cover image on Community Night")
                .contains("could not read the event back to confirm it");
    }

    @Test
    void aCoverFetchedFromAUrlReachesDiscordWithoutAnyFilesystemGrant() {
        // The imageUrl branch is the one that needs no configuration to reach, and until now
        // nothing exercised it through the tool — only the guard it delegates to. FILE_ROOT is
        // left unset on purpose: this path must work with no filesystem grant at all.
        service.coverFileRoot = "";

        String result;
        try (MockedStatic<RemoteFetchGuard> guard = mockStatic(RemoteFetchGuard.class)) {
            guard.when(() -> RemoteFetchGuard.fetch(any(), anyInt(), any())).thenReturn(png());
            result = service.setScheduledEventImage(
                    GUILD, EVENT, "https://cdn.discordapp.com/attachments/1/2/poster.png", null);
        }

        verify(manager).setImage(any(Icon.class));
        assertThat(result).contains("Set the cover image on Community Night");
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
    void anOversizedUrlAndAnOversizedFileFailIdentically() {
        // The multi-catch exists so both guards' size refusals read the same way. RemoteFetchGuard
        // says only "exceeds the maximum allowed size" — no limit, no capital, no period — so
        // without this the two branches diverged for the same mistake.
        service.coverFileRoot = "";

        try (MockedStatic<RemoteFetchGuard> guard = mockStatic(RemoteFetchGuard.class)) {
            guard.when(() -> RemoteFetchGuard.fetch(any(), anyInt(), any())).thenThrow(
                    new RemoteFetchGuard.TooLargeException("cover image exceeds the maximum allowed size"));

            assertThatThrownBy(() -> service.setScheduledEventImage(
                    GUILD, EVENT, "https://cdn.discordapp.com/big.png", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cover image exceeds the 5.0 MB limit.")
                    .hasMessageContaining("Crop it to 5:2");
        }
    }

    @Test
    void anEventNotYetInTheCacheIsNotReportedAsNonexistent() {
        // The flow the tool description steers callers toward — create an event, then cover it
        // from the poster's CDN link — is exactly this case: JDA's cache is filled from the
        // gateway, so the event exists at Discord before it exists here. "Not found by eventId"
        // sends the caller to check an id that is correct.
        //
        // Nothing covered the miss branch before, which is how this helper spent a round wired
        // into edit_guild_scheduled_event instead of this tool, answering edit requests with a
        // sentence about covers. A test on the branch is what keeps that caught.
        when(guild.getScheduledEventById(EVENT)).thenReturn(null);
        service.coverFileRoot = "";

        assertThatThrownBy(() -> service.setScheduledEventImage(
                GUILD, EVENT, "https://cdn.discordapp.com/x.png", null))
                .isInstanceOf(IllegalArgumentException.class)
                // The mocked JDA cannot complete the live read, so the confirmation fails. The
                // property under test is that a failed confirmation is not reported as a verdict:
                // only Discord answering UNKNOWN_SCHEDULED_EVENT establishes that the id is wrong,
                // and this assertion fails the moment anything else starts claiming it does.
                .hasMessageContaining("Could not reach Discord to confirm whether that event exists")
                .hasMessageNotContaining("not found by eventId");
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
        // Each assertion pins the GUARD's own wording rather than just the exception type. An
        // earlier version asserted only `isInstanceOf` and passed a null guildId, so it would
        // have been satisfied by "guildId cannot be null" even against a bare openStream() — a
        // test that cannot fail for the reason it names is worse than no test. The guild and
        // event resolve here, so the fetch is genuinely reached.
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
        assertThat(ScheduledEventService.describeOutcome(null, was, true))
                .contains("was REMOVED during this call")
                .contains(was);
        // Nothing to compare against: no removal can be claimed.
        assertThat(ScheduledEventService.describeOutcome(null, null, true))
                .contains("currently has no cover image");
        assertThat(ScheduledEventService.describeOutcome(was, was, true))
                .contains("still has the cover it had before");
        assertThat(ScheduledEventService.describeOutcome(now, was, true))
                .contains("CHANGED during this call")
                .contains(now)
                // Must NOT claim this request made the change: a genuine rejection followed by a
                // concurrent edit reaches the same branch, and nothing here can tell them apart.
                .contains("something else changed it in the meantime");
        // The previous cover was unreadable, so no comparison is possible and none is implied.
        assertThat(ScheduledEventService.describeOutcome(now, null, false))
                .contains("whether this call changed it is unknown");
        // An event that had no cover and still has none: "not changed" is safe to say here only
        // because the read-back established it, not because the write threw.
        assertThat(ScheduledEventService.describeOutcome(null, null, false))
                .contains("currently has no cover image");
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
                .hasMessageContaining("Cover image exceeds the 5.0 MB limit.")
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
