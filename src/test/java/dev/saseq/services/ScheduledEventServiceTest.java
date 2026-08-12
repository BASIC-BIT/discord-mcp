package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Icon;
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
import static org.mockito.Mockito.mock;

class ScheduledEventServiceTest {

    private ScheduledEventService service;

    @BeforeEach
    void setUp() {
        service = new ScheduledEventService(mock(JDA.class));
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
        assertThat(ScheduledEventService.coverType(png())).isEqualTo(Icon.IconType.PNG);
        assertThat(ScheduledEventService.coverType(jpeg())).isEqualTo(Icon.IconType.JPEG);
    }

    @Test
    void aGifCoverIsRefusedByName() {
        // Discord animates avatars and banners but not event covers, so this is the plausible
        // mistake rather than an exotic one, and the message has to say which.
        assertThatThrownBy(() -> ScheduledEventService.coverType("GIF89a".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be GIFs");
    }

    @Test
    void anythingElseIsRefusedBeforeItReachesDiscord() {
        assertThatThrownBy(() -> ScheduledEventService.coverType("<svg/>".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a PNG or JPEG");
        // A truncated body must not index past its end. Every prefix of a real PNG is a plausible
        // partial download.
        assertThatThrownBy(() -> ScheduledEventService.coverType(new byte[]{(byte) 0x89, 'P'}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduledEventService.coverType(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactlyOneOfImageUrlAndFilePathIsRequired() {
        // Neither: the message leads with imageUrl, because that is the option needing no
        // filesystem grant, and steering callers to it is what keeps deployments off the
        // shared-root configuration.
        assertThatThrownBy(() -> service.setScheduledEventImage(null, "1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Supply either imageUrl")
                .hasMessageContaining("no filesystem access needed");
        assertThatThrownBy(() -> service.setScheduledEventImage(null, "1", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Supply either imageUrl");
        // Both: ambiguous about which one was meant, so neither is guessed at.
        assertThatThrownBy(() -> service.setScheduledEventImage(
                null, "1", "https://cdn.discordapp.com/x.png", "/tmp/x.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not both");
    }

    @Test
    void anImageUrlGoesThroughTheSharedSsrfGuard() {
        // The whole point of offering imageUrl is that it needs no filesystem grant — which makes
        // it the parameter an injected prompt would reach for. It must not become a second,
        // unguarded fetch path; that is the exact regression RemoteFetchGuard exists to prevent.
        service.coverFileRoot = "";

        assertThatThrownBy(() -> service.setScheduledEventImage(
                null, "1", "http://169.254.169.254/latest/meta-data/", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setScheduledEventImage(
                null, "1", "file:///etc/passwd", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLocalPathStillRequiresTheUploadRootButAUrlDoesNot() {
        // Unset FILE_ROOT must not disable the tool outright any more, only its filePath half,
        // and the refusal has to point at the option that still works.
        service.coverFileRoot = "";

        assertThatThrownBy(() -> service.setScheduledEventImage(null, "1", null, "/etc/passwd"))
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

        assertThat(ScheduledEventService.describeOutcome(null, was, true))
                .contains("currently has no cover image");
        assertThat(ScheduledEventService.describeOutcome(was, was, true))
                .contains("still has the cover it had before");
        assertThat(ScheduledEventService.describeOutcome(now, was, true))
                .contains("DID change despite the error")
                .contains(now);
        // The previous cover was unreadable, so no comparison is possible and none is implied.
        assertThat(ScheduledEventService.describeOutcome(now, null, false))
                .contains("whether this call changed it is unknown");
        // An event that had no cover and still has none: "not changed" is safe to say here only
        // because the read-back established it, not because the write threw.
        assertThat(ScheduledEventService.describeOutcome(null, null, false))
                .contains("currently has no cover image");
    }

    @Test
    void settingACoverIsDisabledUntilAnUploadRootIsConfigured(@TempDir Path dir) throws IOException {
        Path file = Files.write(dir.resolve("poster.png"), png());
        service.coverFileRoot = "";

        assertThatThrownBy(() -> service.setScheduledEventImage(null, "1", null, file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISCORD_MCP_FILE_ROOT");
    }

    @Test
    void settingACoverRefusesAPathOutsideTheUploadRoot(@TempDir Path dir) throws IOException {
        // Proves the guard is actually wired in, not merely imported. Without it this tool is a
        // read of any file the process can open, on a service that holds a bot token.
        Path root = Files.createDirectory(dir.resolve("uploads"));
        Path outside = Files.write(dir.resolve("secret.png"), png());
        service.coverFileRoot = root.toString();

        assertThatThrownBy(() -> service.setScheduledEventImage(null, "1", null, outside.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the allowed upload directory");
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

        assertThatThrownBy(() -> service.setScheduledEventImage(null, "1", null, file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 MB limit")
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
