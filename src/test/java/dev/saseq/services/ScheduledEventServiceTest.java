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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

    @BeforeEach
    void setUp() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        ScheduledEvent event = mock(ScheduledEvent.class);
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
        assertThat(ScheduledEventService.coverCaveat(3, 2, 0, 2, 0, true))
                .isEqualTo("\n(2 of 3 events have no cover image; 2 events were not in the live"
                        + " read, so their covers are unknown.)");
    }

    @Test
    void anEventMissingFromTheLiveReadIsSaidSoEvenWhenEveryOtherCoverIsPresent() {
        // The case that made the earlier version wrong: with no coverless events the caveat went
        // empty, so an undescribed event rendered with no cover line and no explanation — exactly
        // "this event has no cover", the claim the described set exists to avoid.
        assertThat(ScheduledEventService.coverCaveat(3, 0, 0, 1, 0, true))
                .isEqualTo("\n(1 event was not in the live read, so its cover is unknown.)");
    }

    @Test
    void anEventDiscordReturnedButTheCacheLacksIsSaidToBeMissingFromTheList() {
        // The stronger skew: such an event has no row at all, so there is nowhere to hang a
        // per-event caveat and the list would otherwise read as complete.
        assertThat(ScheduledEventService.coverCaveat(3, 0, 0, 0, 2, true))
                .isEqualTo("\n(Discord returned 2 events not in this list, so the list is"
                        + " incomplete — the cache has not caught up.)");
    }

    @Test
    void anEntryReturnedButUnreadableIsNotCalledAbsent() {
        // The distinction this set of counters exists for. Discord DID return the event; its
        // details would not parse. Reporting that as "not in the live read" describes a cache lag
        // that did not happen, and sends whoever reads it to look in the wrong place.
        assertThat(ScheduledEventService.coverCaveat(2, 0, 1, 0, 0, true))
                .isEqualTo("\n(1 event was returned but could not be read, so its cover is unknown.)");
        // Both at once, each named as itself.
        assertThat(ScheduledEventService.coverCaveat(2, 1, 1, 1, 0, true))
                .isEqualTo("\n(1 of 2 events have no cover image; 1 event was returned but could"
                        + " not be read, so its cover is unknown; 1 event was not in the live read,"
                        + " so its cover is unknown.)");
    }

    @Test
    void aFullyDescribedListingWithEveryCoverPresentSaysNothing() {
        assertThat(ScheduledEventService.coverCaveat(3, 0, 0, 0, 0, true)).isEmpty();
    }

    @Test
    void theOrdinaryCaseOfNoCoversAtAllSkipsTheArithmetic() {
        // Covers are rare, so "3 of 3 events have no cover image" is what most listings would
        // carry, and the numbers in it say nothing a reader can use.
        assertThat(ScheduledEventService.coverCaveat(3, 3, 0, 0, 0, true))
                .isEqualTo("\n(no event here has a cover image.)");
    }

    @Test
    void aFailedLiveReadCaveatsEverythingRatherThanCounting() {
        // Counts drawn from a read that did not happen are all zero, which would render as "every
        // event has a cover" — the failure mode the caveat exists for.
        assertThat(ScheduledEventService.coverCaveat(0, 0, 0, 0, 0, false))
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
        // mistake rather than an exotic one, and the message has to say which.
        assertThatThrownBy(() -> ScheduledEventService.coverType("GIF89a".getBytes(StandardCharsets.US_ASCII), "filePath"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be GIFs");
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
