package dev.saseq.services;

import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.utils.config.AuthorizationConfig;
import net.dv8tion.jda.api.utils.data.DataObject;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Deque;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.dv8tion.jda.api.entities.Guild;
import java.time.Instant;

@org.junit.jupiter.api.Timeout(15)
class ScheduledEventOccurrenceTest {
    private static final String ANCHOR = "2090-01-02T20:00:00Z";
    private static final String TARGET = "2090-01-09T20:00:00Z";
    private static final String EXCEPTION_ID = Long.toUnsignedString(
            (Instant.parse(TARGET).toEpochMilli() - 1420070400000L) << 22);

    @Test
    void cancelsOneDateAndReadsItBackWithoutChangingTheSeries() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            transport.replies.add(series("[]"));
            transport.replies.add("{}");
            transport.replies.add(series(exception(true, null)));

            var result = DataObject.fromJson(service.cancelScheduledEventOccurrence("123", "234", TARGET));
            assertThat(result.getString("status")).isEqualTo("canceled");
            assertThat(result.getBoolean("seriesConfirmedUnchanged")).isTrue();
            assertThat(result.hasKey("seriesWarning")).isFalse();
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET", "POST", "GET");
            assertThat(transport.requests.get(1).url().encodedPath()).endsWith("/123/scheduled-events/234/exceptions");
            var sent = DataObject.fromJson(transport.bodies.get(1));
            assertThat(sent.keys()).containsExactlyInAnyOrder("original_scheduled_start_time", "is_canceled");
            assertThat(sent.getString("original_scheduled_start_time")).isEqualTo(TARGET);
            assertThat(sent.getBoolean("is_canceled")).isTrue();
        }
    }

    @Test
    void reportsCancellationButWarnsWhenReadbackCannotConfirmTheSeries() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            var saved = series(exception(true, null));

            for (String readback : List.of(
                    saved.replace("\"recurrence_rule\":{\"frequency\":2,\"interval\":1,\"start\":\"" + ANCHOR + "\"},", ""),
                    saved.replace("\"recurrence_rule\":{\"frequency\":2,\"interval\":1,\"start\":\"" + ANCHOR + "\"}",
                            "\"recurrence_rule\":\"unreadable\""),
                    saved.replace("\"interval\":1", "\"interval\":2"),
                    saved.replace("\"status\":1", "\"status\":4"))) {
                transport.replies.add(series("[]"));
                transport.replies.add("{}");
                transport.replies.add(readback);

                var result = DataObject.fromJson(service.cancelScheduledEventOccurrence("123", "234", TARGET));
                assertThat(result.getString("status")).isEqualTo("canceled");
                assertThat(result.getBoolean("seriesConfirmedUnchanged")).isFalse();
                assertThat(result.getString("seriesWarning")).contains("still scheduled with the same recurrence rule");
            }
            assertThat(transport.requests).extracting(Request::method)
                    .containsExactly("GET", "POST", "GET", "GET", "POST", "GET", "GET", "POST", "GET",
                            "GET", "POST", "GET");
        }
    }

    @Test
    void patchesExistingRescheduleAndRepeatDoesNotWrite() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            String moved = "2090-01-11T20:00:00Z";
            transport.replies.add(series(exception(false, moved)));
            transport.replies.add("{}");
            transport.replies.add(series(exception(true, moved)));
            transport.replies.add(series(exception(true, moved)));

            var first = service.cancelScheduledEventOccurrence("123", "234", TARGET);
            var repeat = service.cancelScheduledEventOccurrence("123", "234", TARGET);

            assertThat(DataObject.fromJson(first).getString("status")).isEqualTo("canceled");
            assertThat(DataObject.fromJson(repeat).getString("status")).isEqualTo("already_canceled");
            assertThat(DataObject.fromJson(first).getString("effectiveScheduledStartTime")).isEqualTo(moved);
            assertThat(DataObject.fromJson(repeat).getString("effectiveScheduledStartTime")).isEqualTo(moved);
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET", "PATCH", "GET", "GET");
            assertThat(transport.requests.get(1).url().encodedPath()).endsWith("/exceptions/" + EXCEPTION_ID);
            var sent = DataObject.fromJson(transport.bodies.get(1));
            assertThat(sent.keys()).containsExactly("is_canceled");
            assertThat(sent.getBoolean("is_canceled")).isTrue();
        }
    }

    @Test
    void rejectsNonOccurrenceAndMissingExceptionDataBeforeWriting() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            transport.replies.add(series("[]"));
            transport.replies.add(series("null"));
            assertThatThrownBy(() -> service.cancelScheduledEventOccurrence("123", "234", "2090-01-10T20:00:00Z"))
                    .hasMessageContaining("does not match");
            assertThatThrownBy(() -> service.cancelScheduledEventOccurrence("123", "234", TARGET))
                    .hasMessageContaining("did not return");
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET", "GET");
        }
    }

    @Test
    void doesNotClaimSuccessWhenReadbackDoesNotShowCancellation() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            transport.replies.add(series("[]"));
            transport.replies.add("{}");
            transport.replies.add(series("[]"));
            assertThatThrownBy(() -> service.cancelScheduledEventOccurrence("123", "234", TARGET))
                    .hasMessageContaining("accepted the write but readback did not show");
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET", "POST", "GET");
        }
    }

    @Test
    void reconcilesAConflictingWriteWithoutPostingAgain() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            transport.writeStatus = 409;
            transport.replies.add(series("[]"));
            transport.replies.add("{}");
            transport.replies.add(series(exception(true, null)));
            var result = DataObject.fromJson(service.cancelScheduledEventOccurrence("123", "234", TARGET));
            assertThat(result.getString("status")).isEqualTo("canceled");
            assertThat(result.getBoolean("writeConfirmed")).isFalse();
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET", "POST", "GET");
        }
    }

    @Test
    void includesProviderRefusalWhenReadbackShowsNoCancellation() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            transport.writeStatus = 403;
            transport.replies.add(series("[]"));
            transport.replies.add("{\"code\":50013,\"message\":\"Missing Permissions\"}");
            transport.replies.add(series("[]"));
            assertThatThrownBy(() -> service.cancelScheduledEventOccurrence("123", "234", TARGET))
                    .hasMessageContaining("readback shows the occurrence was not canceled")
                    .hasMessageContaining("Missing Permissions");
        }
    }

    @Test
    void distinguishesUnavailableReadbackFromAConfirmedNonCancellation() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            transport.replies.add(series("[]"));
            transport.replies.add("{}");
            transport.replies.add("not-json");
            assertThatThrownBy(() -> service.cancelScheduledEventOccurrence("123", "234", TARGET))
                    .hasMessageContaining("Could not verify occurrence cancellation")
                    .hasMessageContaining("response is unreadable");
        }
    }

    @Test
    void warnsWhenAnUncanceledExceptionStillOccupiesTheSameTime() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            String moved = DataObject.empty().put("event_id", "234").put("event_exception_id", "435")
                    .put("original_scheduled_start_time", ANCHOR)
                    .put("scheduled_start_time", TARGET).put("is_canceled", false).toString();
            transport.replies.add(series("[" + moved + "]"));
            transport.replies.add("{}");
            transport.replies.add(series(exception(true, null).replace("]", "," + moved + "]")));
            var result = DataObject.fromJson(service.cancelScheduledEventOccurrence("123", "234", TARGET));
            assertThat(result.getString("warning")).isEqualTo("Another occurrence is still scheduled at " + TARGET);
        }
    }

    @Test
    void rejectsUnsupportedRulesAndDatesBeyondTheRuleBounds() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            var base = series("[]");
            for (var invalid : List.of(base.replace("\"frequency\":2", "\"frequency\":3"),
                    base.replace("\"interval\":1", "\"interval\":2"),
                    base.replace("\"interval\":1", "\"interval\":1,\"by_weekday\":[9]"),
                    base.replace("\"interval\":1", "\"interval\":1,\"by_month\":[1]"),
                    base.replace("\"interval\":1", "\"interval\":1,\"count\":1"),
                    base.replace("\"interval\":1", "\"interval\":1,\"end\":\"" + ANCHOR + "\""))) {
                transport.replies.add(invalid);
                assertThatThrownBy(() -> service.cancelScheduledEventOccurrence("123", "234", TARGET))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(transport.requests).hasSize(6).allMatch(request -> request.method().equals("GET"));
        }
    }

    @Test
    void rejectsAnActiveSeriesAndPastDatesWithDifferentMessages() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            transport.replies.add(series("[]").replace("\"status\":1", "\"status\":2"));
            transport.replies.add(series("[]").replace("2090-", "2020-"));
            assertThatThrownBy(() -> service.cancelScheduledEventOccurrence("123", "234", TARGET))
                    .hasMessageContaining("Discord returned status 2");
            assertThatThrownBy(() -> service.cancelScheduledEventOccurrence("123", "234", TARGET.replace("2090-", "2020-")))
                    .hasMessageContaining("Only future occurrences");
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET", "GET");
        }
    }

    @Test
    void doesNotWriteIfDiscordReturnsTheEventUnderADifferentGuild() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            transport.replies.add(series("[]").replace("\"guild_id\":\"123\"", "\"guild_id\":\"999\""));
            assertThatThrownBy(() -> service.cancelScheduledEventOccurrence("123", "234", TARGET))
                    .hasMessageContaining("Could not read the requested scheduled event");
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET");
        }
    }

    @Test
    void coverReadDistinguishesMalformedDiscordDataFromAnUnreachableServer() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            transport.replies.add("not-json");
            assertThatThrownBy(() -> service.setScheduledEventImage("123", "234", "https://example.com/cover.png", null))
                    .hasMessageContaining("Discord returned that event but the response could not be read");
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET");
        }
    }

    @Test
    void usesExplicitOriginalStartWhenDiscordSuppliesIt() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            String saved = DataObject.empty().put("event_id", "234").put("event_exception_id", "435")
                    .put("original_scheduled_start_time", TARGET).put("is_canceled", true).toString();
            transport.replies.add(series("[" + saved + "]"));
            var result = DataObject.fromJson(service.cancelScheduledEventOccurrence("123", "234", TARGET));
            assertThat(result.getString("status")).isEqualTo("already_canceled");
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET");
        }
    }

    @Test
    void rejectsMissingStatusAndConflictingExceptionIdentitiesWithoutWriting() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            String saved = exception(false, null);
            transport.replies.add(series("[]").replace("\"status\":1,", ""));
            transport.replies.add(series(saved.replace("\"event_id\":\"234\"", "\"event_id\":\"998\"")));
            transport.replies.add(series(saved.substring(0, saved.length() - 1) + "," + saved.substring(1)));
            assertThatThrownBy(() -> service.cancelScheduledEventOccurrence("123", "234", TARGET))
                    .hasMessageContaining("readable scheduled event status");
            assertThatThrownBy(() -> service.cancelScheduledEventOccurrence("123", "234", TARGET))
                    .hasMessageContaining("different scheduled event");
            assertThatThrownBy(() -> service.cancelScheduledEventOccurrence("123", "234", TARGET))
                    .hasMessageContaining("duplicate exceptions");
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET", "GET", "GET");
        }
    }

    @Test
    void findsAnOccurrenceMovedIntoTheWindowAndOmitsItFromItsOriginalWindow() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            String moved = "2090-01-17T20:00:00Z";
            String response = series(exception(false, moved));
            transport.replies.add(response);
            transport.replies.add(response);

            var originalWeek = DataObject.fromJson(service.listScheduledEvents("123", null, "234",
                    "2090-01-09T00:00:00Z", "2090-01-16T00:00:00Z"));
            assertThat(originalWeek.getArray("occurrences").length()).isZero();
            var movedWeek = DataObject.fromJson(service.listScheduledEvents("123", null, "234",
                    "2090-01-16T00:00:00Z", "2090-01-23T00:00:00Z"));
            var matches = movedWeek.getArray("occurrences");
            assertThat(matches.length()).isEqualTo(2);
            var rescheduled = matches.getObject(1);
            assertThat(rescheduled.getString("originalScheduledStartTime")).isEqualTo(TARGET);
            assertThat(rescheduled.getString("effectiveScheduledStartTime")).isEqualTo(moved);
            assertThat(rescheduled.getBoolean("canceled")).isFalse();
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET", "GET");
        }
    }

    @Test
    void preservesBothOccurrenceIdentitiesWhenTheirDisplayedStartsCoincide() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            String displayed = "2090-01-16T20:00:00Z";
            transport.replies.add(series(exception(true, displayed)));

            var result = DataObject.fromJson(service.listScheduledEvents("123", null, "234",
                    displayed, "2090-01-23T20:00:00Z"));
            var matches = result.getArray("occurrences");
            assertThat(matches.length()).isEqualTo(2);
            assertThat(matches.getObject(0).getString("originalScheduledStartTime")).isEqualTo(TARGET);
            assertThat(matches.getObject(0).getBoolean("canceled")).isTrue();
            assertThat(matches.getObject(1).getString("originalScheduledStartTime")).isEqualTo(displayed);
            assertThat(matches.getObject(1).getBoolean("canceled")).isFalse();
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET");
        }
    }

    @Test
    void refusesAnUnboundedWindowAndDoesNotTreatMissingExceptionsAsEmpty() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            assertThatThrownBy(() -> service.listScheduledEvents("123", null, "234",
                    "2090-01-01T00:00:00Z", "2090-02-02T00:00:00Z")).hasMessageContaining("31 days");
            assertThatThrownBy(() -> service.listScheduledEvents("123", null, "234", null, null))
                    .hasMessageContaining("eventId, from, and to together");
            assertThat(transport.requests).isEmpty();

            transport.replies.add(series("null"));
            assertThatThrownBy(() -> service.listScheduledEvents("123", null, "234",
                    "2090-01-01T00:00:00Z", "2090-02-01T00:00:00Z")).hasMessageContaining("occurrence exceptions");
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET");
        }
    }

    @Test
    void flagsAnExceptionFromAnOldScheduleAndKeepsCurrentOccurrences() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            String moved = "2090-01-17T20:00:00Z";
            transport.replies.add(series(exception(false, moved)).replace("\"interval\":1", "\"interval\":2"));

            var result = DataObject.fromJson(service.listScheduledEvents("123", null, "234",
                    "2090-01-16T00:00:00Z", "2090-01-23T00:00:00Z"));
            assertThat(result.getBoolean("seriesScheduled")).isTrue();
            var matches = result.getArray("occurrences");
            assertThat(matches.length()).isEqualTo(2);
            assertThat(matches.getObject(0).getBoolean("matchesCurrentRecurrence")).isTrue();
            assertThat(matches.getObject(1).getString("originalScheduledStartTime")).isEqualTo(TARGET);
            assertThat(matches.getObject(1).getBoolean("matchesCurrentRecurrence")).isFalse();
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET");
        }
    }

    @Test
    void ignoresDuplicateExceptionsWhenNeitherIsDisplayedInTheWindow() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            var outsideA = exception(false, "2090-01-10T20:00:00Z");
            var outsideB = exception(false, "2090-01-11T20:00:00Z");
            transport.replies.add(series(outsideA.substring(0, outsideA.length() - 1) + "," + outsideB.substring(1)));

            var result = DataObject.fromJson(service.listScheduledEvents("123", null, "234",
                    "2090-01-16T00:00:00Z", "2090-01-23T00:00:00Z"));
            var occurrences = result.getArray("occurrences");
            assertThat(occurrences.length()).isEqualTo(1);
            assertThat(occurrences.getObject(0).getString("originalScheduledStartTime")).isEqualTo("2090-01-16T20:00:00Z");
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET");
        }
    }

    @Test
    void refusesAConflictingExceptionWhenEitherVersionIsDisplayedInTheWindow() {
        try (var transport = new OccurrenceTransport()) {
            var service = service(transport);
            var outside = exception(false, "2090-01-11T20:00:00Z");
            var inside = exception(false, "2090-01-17T20:00:00Z");
            transport.replies.add(series(outside.substring(0, outside.length() - 1) + "," + inside.substring(1)));
            transport.replies.add(series(inside.substring(0, inside.length() - 1) + "," + outside.substring(1)));

            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> service.listScheduledEvents("123", null, "234",
                        "2090-01-16T00:00:00Z", "2090-01-23T00:00:00Z"))
                        .hasMessageContaining("conflicting exceptions for an occurrence in this window");
            }
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET", "GET");
        }
    }

    private ScheduledEventService service(OccurrenceTransport transport) {
        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn("123");
        doReturn(guild).when(transport.jda).getGuildById("123");
        return new ScheduledEventService(transport.jda);
    }

    private String series(String exceptions) {
        return """
                {"id":"234","guild_id":"123","status":1,
                 "recurrence_rule":{"frequency":2,"interval":1,"start":"%s"},
                 "guild_scheduled_event_exceptions":%s}
                """.formatted(ANCHOR, exceptions);
    }

    private String exception(boolean canceled, String effectiveStart) {
        var data = DataObject.empty().put("event_id", "234").put("event_exception_id", EXCEPTION_ID).put("is_canceled", canceled);
        if (effectiveStart != null) {
            data.put("scheduled_start_time", effectiveStart);
        }
        return "[" + data + "]";
    }
}

class OccurrenceTransport implements AutoCloseable {
    final JDAImpl jda;
    final List<Request> requests = new ArrayList<>();
    final List<String> bodies = new ArrayList<>();
    final Deque<String> replies = new ArrayDeque<>();

    int writeStatus = 200;

    OccurrenceTransport() {
        jda = spy(new JDAImpl(new AuthorizationConfig("synthetic-test-token")));
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            requests.add(request);
            okio.Buffer buffer = new okio.Buffer();
            if (request.body() != null) request.body().writeTo(buffer);
            bodies.add(buffer.readUtf8());
            String body = replies.isEmpty() ? "{}" : replies.removeFirst();
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(request.method().equals("GET") ? 200 : writeStatus)
                    .message("OK")
                    .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
        }).build();
        doReturn(http).when(jda).getHttpClient();
        var threading = (net.dv8tion.jda.internal.utils.config.ThreadingConfig)
                org.springframework.test.util.ReflectionTestUtils.getField(jda, "threadConfig");
        threading.init(() -> "offline-event-test");
        jda.initRequester();
    }

    @Override public void close() { jda.shutdownNow(); }
}
