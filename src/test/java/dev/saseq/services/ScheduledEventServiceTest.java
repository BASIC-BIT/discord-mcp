package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        ScheduledEvent event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        OffsetDateTime end = service.resolveEndTime(event, "2026-09-02T20:00:00-05:00", null);

        assertThat(end).isEqualTo(OffsetDateTime.parse("2026-09-02T21:30:00-05:00"));
    }

    @Test
    void anExplicitEndTimeWins() {
        ScheduledEvent event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        OffsetDateTime end = service.resolveEndTime(
                event, "2026-09-02T20:00:00-05:00", "2026-09-02T23:00:00-05:00");

        assertThat(end).isEqualTo(OffsetDateTime.parse("2026-09-02T23:00:00-05:00"));
    }

    @Test
    void anExplicitEndTimeAppliesWithoutMovingTheStart() {
        // Lengthening an event in place: no start move, so there is no delta to apply, but the
        // caller's end must still be honoured.
        ScheduledEvent event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        OffsetDateTime end = service.resolveEndTime(event, null, "2026-08-05T22:00:00-05:00");

        assertThat(end).isEqualTo(OffsetDateTime.parse("2026-08-05T22:00:00-05:00"));
    }

    @Test
    void anEditThatTouchesNeitherTimeLeavesTheEndAlone() {
        ScheduledEvent event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        assertThat(service.resolveEndTime(event, null, null)).isNull();
        assertThat(service.resolveEndTime(event, "", "")).isNull();
    }

    @Test
    void anEventWithNoEndTimeDoesNotGainOne() {
        // Stage and voice events have no end time. Inventing one would impose a constraint the
        // event did not have, and Discord would start enforcing it.
        ScheduledEvent event = event("2026-08-05T20:00:00-05:00", null);

        assertThat(service.resolveEndTime(event, "2026-09-02T20:00:00-05:00", null)).isNull();
    }

    @Test
    void durationIsPreservedAcrossADaylightSavingBoundary() {
        // 2026-11-01 is when US clocks go back. A 90-minute class moved across it should still
        // run 90 minutes — shifting the wall-clock end instead would make it 30 minutes longer.
        ScheduledEvent event = event("2026-10-28T20:00:00-05:00", "2026-10-28T21:30:00-05:00");

        OffsetDateTime end = service.resolveEndTime(event, "2026-11-04T20:00:00-06:00", null);

        assertThat(end).isEqualTo(OffsetDateTime.parse("2026-11-04T21:30:00-06:00"));
        assertThat(java.time.Duration.between(OffsetDateTime.parse("2026-11-04T20:00:00-06:00"), end))
                .isEqualTo(java.time.Duration.ofMinutes(90));
    }

    @Test
    void aMalformedEndTimeIsRejectedRatherThanIgnored() {
        ScheduledEvent event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        assertThatThrownBy(() -> service.resolveEndTime(event, null, "next tuesday"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid ISO8601 timestamp");
    }

    private ScheduledEvent event(String start, String end) {
        ScheduledEvent event = mock(ScheduledEvent.class);
        when(event.getStartTime()).thenReturn(OffsetDateTime.parse(start));
        when(event.getEndTime()).thenReturn(end == null ? null : OffsetDateTime.parse(end));
        return event;
    }
}
