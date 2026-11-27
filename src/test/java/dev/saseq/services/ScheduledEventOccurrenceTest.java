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

            assertThat(DataObject.fromJson(service.cancelScheduledEventOccurrence("123", "234", TARGET))
                    .getString("status")).isEqualTo("canceled");
            assertThat(transport.requests).extracting(Request::method).containsExactly("GET", "POST", "GET");
            assertThat(transport.requests.get(1).url().encodedPath()).endsWith("/123/scheduled-events/234/exceptions");
            var sent = DataObject.fromJson(transport.bodies.get(1));
            assertThat(sent.keys()).containsExactlyInAnyOrder("original_scheduled_start_time", "is_canceled");
            assertThat(sent.getString("original_scheduled_start_time")).isEqualTo(TARGET);
            assertThat(sent.getBoolean("is_canceled")).isTrue();
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
            assertThat(DataObject.fromJson(service.cancelScheduledEventOccurrence("123", "234", TARGET))
                    .getString("status")).isEqualTo("canceled");
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
                    base.replace("\"interval\":1", "\"interval\":1,\"count\":1"),
                    base.replace("\"interval\":1", "\"interval\":1,\"end\":\"" + ANCHOR + "\""))) {
                transport.replies.add(invalid);
                assertThatThrownBy(() -> service.cancelScheduledEventOccurrence("123", "234", TARGET))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(transport.requests).hasSize(5).allMatch(request -> request.method().equals("GET"));
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
