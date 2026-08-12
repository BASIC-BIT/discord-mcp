package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.Method;
import net.dv8tion.jda.api.requests.Route;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.requests.RestActionImpl;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ScheduledEventService {

    private final JDA jda;

    static final String RECURRENCE_PARAM =
            "Recurrence rule as JSON. {\"frequency\": 2} is usually all you need: it recurs weekly "
                    + "on whatever the start time falls on. frequency: 0=yearly, 1=monthly, 2=weekly, 3=daily. "
                    + "by_weekday: 0=Monday..6=Sunday, and a weekly rule accepts exactly one day. "
                    + "IMPORTANT: Discord evaluates recurrence against the UTC date of the start time, "
                    + "not its local date, so a 22:00 US-Eastern event recurs on the FOLLOWING weekday. "
                    + "Prefer to omit the selector: for weekly, monthly and yearly it is derived from the "
                    + "start time correctly, and a supplied one must match the UTC date or it is rejected. "
                    + "interval may exceed 1 only for weekly (every-other-week). "
                    + "by_n_weekday is monthly only; by_month with by_month_day is yearly only. "
                    + "count, end and by_year_day are set by Discord and must be omitted. "
                    + "Defaults its start to the event's start time. "
                    + "On edit, pass \"null\" to remove recurrence and make the event a one-off; "
                    + "omitting this parameter leaves any existing recurrence untouched.";

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    /**
     * The only directory {@code set_guild_scheduled_event_image} may read a cover from.
     *
     * <p>The same variable {@code send_file} uses, and a separate field rather than a shared bean
     * because Spring injects per-bean. Unset disables only the {@code filePath} half of that
     * tool; {@code imageUrl} needs no filesystem access and keeps working. Package-private so
     * tests can set it without Spring.
     */
    @Value("${DISCORD_MCP_FILE_ROOT:}")
    String coverFileRoot;

    /**
     * A local pre-check on cover size, set deliberately low.
     *
     * <p>What is known: Discord does not document a ceiling for this endpoint; {@link Icon}
     * base64-encodes the body into a JSON PATCH, so the request is about a third larger than the
     * file; and oversized JSON bodies are refused with error 40005 at a threshold that is also
     * undocumented. What that adds up to is a band of files that pass a generous local check and
     * are then rejected remotely, after the upload has been spent.
     *
     * <p>So this is not an attempt to mirror Discord's limit. It is sized against what a cover
     * actually is: an image displayed at 800x320, which is around a megabyte as a 2048-wide PNG.
     * Several times that is already far past anything that has been cropped and scaled for the
     * slot, so rejecting it locally — with advice, before spending the upload — is more useful
     * than forwarding it. Raise it if a real ceiling is ever established; the failure it trades
     * away is the expensive one.
     *
     * <p>Tripping it is an ordinary outcome rather than an unusual one: a source poster is
     * commonly a full-resolution square or portrait master that has to be cropped to the display
     * shape anyway, and those routinely exceed it.
     */
    private static final int MAX_COVER_BYTES = 5 * 1024 * 1024;

    public ScheduledEventService(JDA jda) {
        this.jda = jda;
    }

    private String resolveGuildId(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            return defaultGuildId;
        }
        return guildId;
    }

    private Guild getGuild(String guildId) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }
        return guild;
    }

    private ScheduledEvent getEvent(Guild guild, String eventId) {
        if (eventId == null || eventId.isEmpty()) {
            throw new IllegalArgumentException("eventId cannot be null");
        }
        ScheduledEvent event = guild.getScheduledEventById(eventId);
        if (event == null) {
            throw new IllegalArgumentException("Scheduled event not found by eventId");
        }
        return event;
    }

    private OffsetDateTime parseTime(String time) {
        try {
            return OffsetDateTime.parse(time);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid ISO8601 timestamp: " + time);
        }
    }

    /**
     * Read a scheduled event as raw JSON.
     *
     * <p>JDA has no representation for {@code recurrence_rule}, so a recurring event is
     * indistinguishable from a one-off through the normal entity. Routed through JDA's own request
     * stack rather than a separate HTTP client, so it shares the bot token, the rate limiter, and
     * the retry behaviour instead of quietly becoming an unmetered second path to Discord.
     */
    private DataObject fetchRaw(String guildId, String eventId) {
        Route.CompiledRoute route = Route.custom(Method.GET, "guilds/{guild_id}/scheduled-events/{event_id}")
                .compile(guildId, eventId);
        return new RestActionImpl<DataObject>(jda, route,
                (response, request) -> response.getObject()).complete();
    }

    private DataObject patchRaw(String guildId, String eventId, DataObject body) {
        Route.CompiledRoute route = Route.custom(Method.PATCH, "guilds/{guild_id}/scheduled-events/{event_id}")
                .compile(guildId, eventId);
        return new RestActionImpl<DataObject>(jda, route, body,
                (response, request) -> response.getObject()).complete();
    }

    /** The event's recurrence rule, or null if it is not a recurring event. */
    private DataObject recurrenceOf(DataObject raw) {
        // Tolerates an absent key as well as an explicit null, so it is safe to call with the empty
        // object used when a best-effort read failed.
        return !raw.hasKey("recurrence_rule") || raw.isNull("recurrence_rule")
                ? null
                : raw.getObject("recurrence_rule");
    }

    /**
     * Apply a recurrence change, reporting precisely what already landed if it fails.
     *
     * <p>The ordinary fields are written by JDA's manager before this runs, so a failure here
     * leaves a half-applied edit. Saying which half is the difference between a caller who can fix
     * it and one who has to go and look. Creation can compensate by deleting the event it just
     * made; an edit has nothing equivalent to undo, and an honest report beats a rollback that
     * would itself be a second fallible write.
     */
    private void patchRecurrence(Guild guild, ScheduledEvent event, DataObject body, String applied) {
        try {
            patchRaw(guild.getId(), event.getId(), body);
        } catch (RuntimeException e) {
            // A thrown request does not prove the change did not happen — a lost response after
            // Discord processed the PATCH looks identical from here. Rather than assert an outcome
            // we cannot know, read the event back and report what is actually true.
            String outcome;
            try {
                DataObject after = fetchRaw(guild.getId(), event.getId());
                DataObject rule = recurrenceOf(after);
                outcome = rule == null
                        ? " The event is currently not recurring."
                        : " The event currently recurs: " + RecurrenceRule.describe(rule) + ".";
            } catch (RuntimeException unverifiable) {
                outcome = " Could not read the event back, so whether the recurrence change applied is"
                        + " unknown — check the event before retrying.";
            }
            throw new IllegalArgumentException(
                    "The recurrence change failed: " + e.getMessage() + ". "
                            + (applied.isEmpty()
                            ? "Nothing else was changed."
                            : "These changes were already applied and remain in effect: " + applied + ".")
                            + outcome);
        }
    }

    /** Human list of the fields JDA's manager has already written. */
    private String describeApplied(String name, String description, String scheduledStartTime,
                                   OffsetDateTime endTime, String location, Integer statusCode) {
        List<String> parts = new java.util.ArrayList<>();
        if (name != null && !name.isEmpty()) parts.add("name");
        if (description != null && !description.isEmpty()) parts.add("description");
        if (scheduledStartTime != null && !scheduledStartTime.isEmpty()) parts.add("start time");
        if (endTime != null) parts.add("end time");
        if (location != null && !location.isEmpty()) parts.add("location");
        if (statusCode != null) parts.add("status");
        return String.join(", ", parts);
    }

    /**
     * Works out the end time an edit should apply, which is usually one the caller did not supply.
     *
     * <p>Discord stores start and end independently and validates that end is after start, so
     * moving a dated event's start without its end is rejected outright — the failure the staff
     * agent hit trying to shift three weekly classes four weeks out. Nothing in the rejection says
     * the end time is the problem, and the obvious workaround is to delete and recreate the
     * series, which loses its ID, its subscribers, and its history.
     *
     * <p>So an omitted {@code scheduledEndTime} means "keep the duration", not "leave the end
     * alone": the end moves by the same amount as the start. That is what shifting an event
     * already means to whoever asked for it. Preserving the elapsed duration rather than the
     * wall-clock end is deliberate — an event moved across a DST boundary should still run for an
     * hour, not for fifty-nine minutes or sixty-one.
     *
     * @param raw the live event as returned by {@link #fetchRaw}, which is the authority for
     *            the current times — JDA's cached entity can lag an out-of-band edit
     * @return the end time to set, or {@code null} to leave it untouched
     */
    // Package-private so the duration-preserving cases can be tested without a live event.
    OffsetDateTime resolveEndTime(DataObject raw, String scheduledStartTime, String scheduledEndTime) {
        boolean movingStart = scheduledStartTime != null && !scheduledStartTime.isEmpty();
        // Read from the live GET rather than JDA's cache. The cached entity can be behind an
        // out-of-band edit, or behind an earlier edit whose gateway update has not arrived yet,
        // and a stale duration would then be applied silently — or a stale null end would skip
        // the shift entirely and let the move fail exactly as it did before this existed. The
        // caller has already fetched this, and whenever the start is moving that fetch is
        // guaranteed to have succeeded, because a failed read throws before reaching here.
        String currentStart = raw.getString("scheduled_start_time", null);
        String currentEnd = raw.getString("scheduled_end_time", null);

        OffsetDateTime effectiveStart = movingStart
                ? parseTime(scheduledStartTime)
                : (currentStart == null ? null : parseTime(currentStart));

        if (scheduledEndTime != null && !scheduledEndTime.isEmpty()) {
            OffsetDateTime end = parseTime(scheduledEndTime);
            // Discord rejects the whole manager update for an end at or before the start, with
            // the same opaque server-side error this parameter exists to stop people hitting.
            if (effectiveStart != null && !end.isAfter(effectiveStart)) {
                throw new IllegalArgumentException(
                        "scheduledEndTime " + scheduledEndTime + " is not after the start time "
                                + effectiveStart + (movingStart
                                ? ", which this call is moving the event to. Move the end past it, or "
                                + "omit it and it will follow the start automatically."
                                : ". Pass scheduledStartTime too if you meant to move both."));
            }
            return end;
        }

        if (!movingStart) {
            return null;
        }
        // Stage and voice events carry no end time at all, so there is no duration to preserve
        // and setting one would invent a constraint the event did not have.
        if (currentStart == null || currentEnd == null) {
            return null;
        }
        return effectiveStart.plus(Duration.between(parseTime(currentStart), parseTime(currentEnd)));
    }

    /**
     * Whether two ISO8601 strings denote the same moment.
     *
     * <p>String equality is wrong here. Discord normalises the timestamps it returns, so the value
     * from a raw GET routinely differs textually from the one the caller sent for the same instant
     * — {@code 2026-08-06T01:00:00+00:00} against {@code 2026-08-05T20:00:00-05:00}. Comparing text
     * would reject correct input.
     */
    private boolean sameInstant(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return OffsetDateTime.parse(a).toInstant().equals(OffsetDateTime.parse(b).toInstant());
        } catch (DateTimeParseException e) {
            return a.equals(b);
        }
    }

    /**
     * Parse the status parameter once, so the terminal-transition guard and the setter can never
     * disagree about what the caller asked for.
     *
     * @return the status code, or null when no status change was requested
     */
    private Integer parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        int code;
        try {
            code = Integer.parseInt(status.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "status must be 1 (Scheduled), 2 (Active), 3 (Completed), or 4 (Canceled), got: " + status);
        }
        if (code < 1 || code > 4) {
            throw new IllegalArgumentException(
                    "status must be 1 (Scheduled), 2 (Active), 3 (Completed), or 4 (Canceled), got: " + code);
        }
        return code;
    }

    /** Whether two rules pick the same point in the cycle, ignoring the anchor timestamp. */
    private boolean sameSelectors(DataObject a, DataObject b) {
        for (String selector : List.of("by_weekday", "by_n_weekday", "by_month", "by_month_day")) {
            boolean hasLeft = a.hasKey(selector) && !a.isNull(selector);
            boolean hasRight = b.hasKey(selector) && !b.isNull(selector);
            if (hasLeft != hasRight) {
                return false;
            }
            if (!hasLeft) {
                continue;
            }
            if (selector.equals("by_n_weekday")) {
                // Field by field, not serialised text: Discord may return {"day":2,"n":1} where we
                // build {"n":1,"day":2}, and reporting that as a schedule change would be a false
                // alarm on the loudest message this tool produces.
                DataObject left = a.getArray(selector).getObject(0);
                DataObject right = b.getArray(selector).getObject(0);
                if (left.getInt("n", -1) != right.getInt("n", -1)
                        || left.getInt("day", -1) != right.getInt("day", -1)) {
                    return false;
                }
                continue;
            }
            if (!a.getArray(selector).toString().equals(b.getArray(selector).toString())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the caller is asking to turn a recurring event back into a one-off.
     *
     * <p>Needs its own spelling because an absent parameter already means "leave recurrence alone",
     * so there is no way to express {@code recurrence_rule: null} otherwise.
     */
    private boolean isClearRequest(String recurrenceRule) {
        if (recurrenceRule == null) {
            return false;
        }
        String trimmed = recurrenceRule.trim();
        return trimmed.equalsIgnoreCase("null") || trimmed.equalsIgnoreCase("none");
    }

    private String formatEvent(ScheduledEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(event.getName()).append("** (ID: ").append(event.getId()).append(")\n");
        sb.append("  • Type: ").append(event.getType()).append("\n");
        sb.append("  • Status: ").append(event.getStatus()).append("\n");
        sb.append("  • Start: ").append(event.getStartTime());
        if (event.getEndTime() != null) sb.append("\n  • End: ").append(event.getEndTime());
        if (event.getChannel() != null) {
            sb.append("\n  • Channel: ").append(event.getChannel().getName())
                    .append(" (ID: ").append(event.getChannel().getId()).append(")");
        }
        if (event.getLocation() != null && !event.getLocation().isEmpty()) {
            sb.append("\n  • Location: ").append(event.getLocation());
        }
        if (event.getDescription() != null && !event.getDescription().isEmpty()) {
            sb.append("\n  • Description: ").append(event.getDescription());
        }
        // No cover line here on purpose. formatEvent's only caller is createScheduledEvent, on an
        // event made moments earlier by a call that cannot set an image, so it could only ever
        // print "none". listScheduledEvents reports the cover, from a live read.
        sb.append("\n  • Interested: ").append(event.getInterestedUserCount()).append(" users");
        return sb.toString();
    }

    @Tool(name = "create_guild_scheduled_event", description = "Schedule a new event on the server (voice, stage, or external)")
    public String createScheduledEvent(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Name of the event") String name,
            @ToolParam(description = "Description of the event", required = false) String description,
            @ToolParam(description = "ISO8601 timestamp for when the event starts") String scheduledStartTime,
            @ToolParam(description = "ISO8601 timestamp for when the event ends (Required for External events)", required = false) String scheduledEndTime,
            @ToolParam(description = "Type of event: 1=Stage Instance, 2=Voice, 3=External") String entityType,
            @ToolParam(description = "Channel ID (Required for types 1 and 2)", required = false) String channelId,
            @ToolParam(description = "Location or link (Required for type 3 - External)", required = false) String location,
            @ToolParam(description = RECURRENCE_PARAM, required = false) String recurrenceRule) {

        Guild guild = getGuild(guildId);
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("name cannot be null");
        if (entityType == null || entityType.isEmpty()) throw new IllegalArgumentException("entityType cannot be null");

        int type = Integer.parseInt(entityType);
        OffsetDateTime startTime = parseTime(scheduledStartTime);

        var action = switch (type) {
            case 1, 2 -> {
                if (channelId == null || channelId.isEmpty())
                    throw new IllegalArgumentException("channelId is required for Stage and Voice events");
                GuildChannel channel = guild.getGuildChannelById(channelId);
                if (channel == null) throw new IllegalArgumentException("Channel not found by channelId");
                yield guild.createScheduledEvent(name, channel, startTime);
            }
            case 3 -> {
                if (location == null || location.isEmpty())
                    throw new IllegalArgumentException("location is required for External events");
                if (scheduledEndTime == null || scheduledEndTime.isEmpty())
                    throw new IllegalArgumentException("scheduledEndTime is required for External events");
                yield guild.createScheduledEvent(name, location, startTime, parseTime(scheduledEndTime));
            }
            default -> throw new IllegalArgumentException("entityType must be 1 (Stage), 2 (Voice), or 3 (External)");
        };

        if (description != null && !description.isEmpty()) action.setDescription(description);
        if (type != 3 && scheduledEndTime != null && !scheduledEndTime.isEmpty()) {
            action.setEndTime(parseTime(scheduledEndTime));
        }

        // Validate the rule BEFORE creating anything. Otherwise a bad rule leaves a stray
        // non-recurring event behind that the caller then has to notice and clean up.
        DataObject rule = (recurrenceRule != null && !recurrenceRule.isEmpty())
                ? RecurrenceRule.parse(recurrenceRule, scheduledStartTime)
                : null;
        // Same disagreement the edit path refuses: an event created at one time whose series is
        // anchored at another follows the anchor, or fails the PATCH and leaves a stray one-off.
        if (rule != null && !sameInstant(rule.getString("start", null), scheduledStartTime)) {
            throw new IllegalArgumentException(
                    "scheduledStartTime is " + scheduledStartTime + " but the recurrence rule anchors at "
                            + rule.getString("start", "") + ". They must match. Omit start from the rule to "
                            + "have it default to the event's start time.");
        }

        ScheduledEvent event = action.complete();
        String formatted = formatEvent(event);

        if (rule != null) {
            // JDA's create action has no recurrence setter, so the field is applied as a follow-up
            // PATCH on the event it just made. That leaves a window: local validation cannot
            // predict a transient REST failure or a guild-level rejection, and without cleanup the
            // caller is left with a one-off event they did not ask for, plus a thrown error that
            // invites a retry and a duplicate.
            try {
                patchRaw(guild.getId(), event.getId(), DataObject.empty().put("recurrence_rule", rule));
            } catch (RuntimeException e) {
                try {
                    event.delete().complete();
                } catch (RuntimeException cleanupFailure) {
                    throw new IllegalArgumentException(
                            "Failed to apply the recurrence rule (" + e.getMessage() + ") and could not remove "
                                    + "the event created for it (ID: " + event.getId() + "). Delete it manually.");
                }
                throw new IllegalArgumentException(
                        "Failed to apply the recurrence rule, so the event was not created: " + e.getMessage());
            }
            formatted += "\n  • Recurrence: " + RecurrenceRule.describe(rule);
        }
        return "Created scheduled event:\n" + formatted;
    }

    @Tool(name = "edit_guild_scheduled_event", description = "Modify details of an existing event or change its status (start, complete, cancel)")
    public String editScheduledEvent(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the scheduled event") String eventId,
            @ToolParam(description = "New status: 1=Scheduled, 2=Active (start), 3=Completed, 4=Canceled", required = false) String status,
            @ToolParam(description = "New name", required = false) String name,
            @ToolParam(description = "New description", required = false) String description,
            @ToolParam(description = "New ISO8601 start time. If the event recurs, its recurrence anchor is moved to match, so the series actually changes rather than snapping back. When scheduledEndTime is omitted the end time shifts by the same amount, so the event keeps its current duration.", required = false) String scheduledStartTime,
            @ToolParam(description = "New ISO8601 end time. Optional. Omit it when moving scheduledStartTime and the end is shifted by the same amount automatically, preserving the current duration — supply it only to change how long the event runs.", required = false) String scheduledEndTime,
            @ToolParam(description = "New location (for External events)", required = false) String location,
            @ToolParam(description = RECURRENCE_PARAM, required = false) String recurrenceRule) {

        Guild guild = getGuild(guildId);
        ScheduledEvent event = getEvent(guild, eventId);

        // Read the live event before touching it: JDA cannot tell us whether this is a recurring
        // series, and that changes what a start-time edit means.
        boolean movingStart = scheduledStartTime != null && !scheduledStartTime.isEmpty();
        // Needed only when recurrence is actually in play: to know whether a start move is also a
        // series move, or whether there is a rule to clear. For a plain rename it feeds nothing but
        // an informational line, and a transient failure on this route must not stop an otherwise
        // independent edit from being attempted at all.
        boolean recurrenceRelevant = movingStart
                || (recurrenceRule != null && !recurrenceRule.isEmpty());
        DataObject raw;
        boolean recurrenceReadFailed = false;
        String recurrenceReadError = null;
        try {
            raw = fetchRaw(guild.getId(), event.getId());
        } catch (RuntimeException e) {
            if (recurrenceRelevant) {
                throw new IllegalArgumentException(
                        "Could not read the event's current recurrence, which this edit depends on"
                                + (e.getMessage() == null ? "" : ": " + e.getMessage())
                                + ". Nothing was changed.");
            }
            raw = DataObject.empty();
            recurrenceReadFailed = true;
            recurrenceReadError = e.getMessage();
        }
        DataObject existingRecurrence = recurrenceOf(raw);

        // Validate the recurrence BEFORE anything is persisted. manager.complete() below is not
        // undoable, so parsing afterwards would report failure on a request that had already
        // applied the name/description/time half of the edit.
        boolean clearingRecurrence = isClearRequest(recurrenceRule);
        DataObject newRule = null;
        if (recurrenceRule != null && !recurrenceRule.isEmpty() && !clearingRecurrence) {
            String anchor = movingStart ? scheduledStartTime : raw.getString("scheduled_start_time", null);
            newRule = RecurrenceRule.parse(recurrenceRule, anchor);
            // A rule may carry its own start, which parse() keeps. If that disagrees with the
            // event's start time the series follows the anchor and the event time is ignored —
            // the snap-back this tool promises to prevent. Checked against the effective start
            // whether or not this call is moving it: a recurrence-only edit can drag the anchor
            // away from an unchanged scheduled_start_time just as easily.
            if (!sameInstant(newRule.getString("start", null), anchor)) {
                throw new IllegalArgumentException(
                        "The event starts at " + anchor + " but the recurrence rule anchors at "
                                + newRule.getString("start", "") + ". They must match, or the series would "
                                + "follow the anchor and ignore the event's start time. Omit start from the "
                                + "rule to have it default to the event's start time"
                                + (movingStart ? ", or set scheduledStartTime to match." : "."));
            }
        }
        if (clearingRecurrence && existingRecurrence == null) {
            throw new IllegalArgumentException(
                    "This event is not recurring, so there is no recurrence rule to clear.");
        }

        // Completing or cancelling is irreversible and leaves an event that cannot then be
        // modified. manager.complete() runs before the recurrence PATCH, so this combination would
        // apply the terminal transition, fail the recurrence write, and report overall failure on
        // a change that had already half happened and cannot be undone.
        // Parsed once and reused below. Comparing the raw string here while applying
        // Integer.parseInt later meant "03" or "+3" read as non-terminal to the guard and as
        // COMPLETED to the setter, which is precisely the combination the guard exists to stop.
        Integer statusCode = parseStatus(status);
        boolean terminalStatus = statusCode != null && (statusCode == 3 || statusCode == 4);
        // The implicit anchor move counts too: moving the start of a recurring event triggers a
        // recurrence PATCH even with no recurrenceRule supplied, and that write would land after
        // the terminal transition just the same.
        boolean touchesRecurrence = newRule != null || clearingRecurrence
                || (existingRecurrence != null && movingStart);
        if (terminalStatus && touchesRecurrence) {
            throw new IllegalArgumentException(
                    "Refusing to change recurrence while completing or cancelling this event. The status "
                            + "change cannot be undone and a terminal event cannot then be edited. Note that "
                            + "moving scheduledStartTime on a recurring event also changes its recurrence. "
                            + "Do the recurrence change first, or drop it from this call.");
        }

        OffsetDateTime newEnd = resolveEndTime(raw, scheduledStartTime, scheduledEndTime);

        var manager = event.getManager();
        if (name != null && !name.isEmpty()) manager.setName(name);
        if (description != null && !description.isEmpty()) manager.setDescription(description);
        if (movingStart) manager.setStartTime(parseTime(scheduledStartTime));
        if (newEnd != null) manager.setEndTime(newEnd);
        if (location != null && !location.isEmpty()) manager.setLocation(location);
        if (statusCode != null) {
            manager.setStatus(switch (statusCode) {
                case 1 -> ScheduledEvent.Status.SCHEDULED;
                case 2 -> ScheduledEvent.Status.ACTIVE;
                case 3 -> ScheduledEvent.Status.COMPLETED;
                case 4 -> ScheduledEvent.Status.CANCELED;
                default -> throw new IllegalArgumentException("status must be 1 (Scheduled), 2 (Active), 3 (Completed), or 4 (Canceled)");
            });
        }

        manager.complete();
        StringBuilder result = new StringBuilder("Successfully updated scheduled event: ")
                .append(event.getName()).append(" (ID: ").append(event.getId()).append(")");

        // Everything above is already persisted. The recurrence write below is a separate request
        // and can still fail for reasons validation cannot foresee, so the failure has to say which
        // half landed. Creation can compensate by deleting the event it just made; an edit has
        // nothing equivalent to undo, and the honest report is worth more than a rollback that
        // would itself be a second fallible write.
        String applied = describeApplied(name, description, scheduledStartTime, newEnd, location, statusCode);

        if (clearingRecurrence) {
            patchRecurrence(guild, event, DataObject.empty().putNull("recurrence_rule"), applied);
            result.append("\n  • Recurrence removed. This is now a one-off event.");
        } else if (newRule != null) {
            patchRecurrence(guild, event, DataObject.empty().put("recurrence_rule", newRule), applied);
            result.append("\n  • Recurrence set: ").append(RecurrenceRule.describe(newRule));
        } else if (existingRecurrence != null && movingStart) {
            // The bug this tool used to have. A recurring event's series is anchored by
            // recurrence_rule.start, not by scheduled_start_time. Moving only the latter shifts the
            // next occurrence and then the series snaps back to its old time, while the tool
            // reported plain success. Move the anchor with it and say so.
            //
            // withStart rebuilds the rule from writable fields only: the GET that produced
            // existingRecurrence also returns count/end/by_year_day, which Discord owns and
            // rejects on the way back in.
            DataObject moved = RecurrenceRule.withStart(existingRecurrence, scheduledStartTime);
            patchRecurrence(guild, event, DataObject.empty().put("recurrence_rule", moved), applied);
            result.append("\n  • This is a recurring event, so its recurrence anchor was moved to ")
                    .append(scheduledStartTime)
                    .append(" as well. Without that the series would have snapped back to the old time.");
            String before = RecurrenceRule.describe(existingRecurrence);
            String after = RecurrenceRule.describe(moved);
            if (!sameSelectors(existingRecurrence, moved)) {
                // Changing which day a weekly class lands on is a bigger deal than a time shift,
                // and it happened as a side effect of the requested move. Say it loudly.
                result.append("\n  • The new date falls on a different part of the cycle, so the series now runs on a ")
                        .append("different schedule. Was: ").append(before).append(". Now: ").append(after)
                        .append(". Pass an explicit recurrenceRule if that is not what you wanted.");
            } else {
                result.append("\n  • Recurrence is now: ").append(after);
            }
        } else if (recurrenceReadFailed) {
            // listScheduledEvents refuses to let a failed read read as "nothing recurs", and this
            // path must not either: the note below is the one that stops a recurring event from
            // being edited as though it were a one-off.
            // Flagged by its own boolean rather than by the message being non-null: getMessage()
            // can return null, and keying off it made this note vanish entirely, rendering as
            // "does not recur" — the one thing the comment above says this path must never do.
            result.append("\n  • Note: this event's recurrence could not be read")
                    .append(recurrenceReadError == null ? "" : " (" + recurrenceReadError + ")")
                    .append(", so it may be a recurring event that is not reported here.");
        } else if (existingRecurrence != null) {
            result.append("\n  • Note: this is a recurring event (")
                    .append(RecurrenceRule.describe(existingRecurrence))
                    .append("). The recurrence rule was not changed.");
        }
        return result.toString();
    }

    /**
     * Deliberately its own tool rather than an {@code image} parameter on
     * {@code edit_guild_scheduled_event}.
     *
     * <p>That tool is already granted wherever events are managed at all, and it reaches nothing
     * but the Discord API. Adding an image parameter would extend it to the local filesystem
     * without the grant changing, so every deployment that allowed event editing would silently
     * acquire a local-file read. Splitting it keeps the two decisions separate: a deployment can
     * allow event edits and refuse cover uploads.
     *
     * <p>Takes {@code imageUrl} as well as {@code filePath} for a related reason. With only a
     * local path, the ordinary job — put a poster that is already in Discord onto an event —
     * requires a filesystem grant, and the shortest route to one is pointing
     * {@code DISCORD_MCP_FILE_ROOT} at the download directory, which is exactly the widening the
     * README argues against. A tool whose safe configuration is the inconvenient one gets
     * configured unsafely.
     */
    @Tool(name = "set_guild_scheduled_event_image", description = "Replace a scheduled event's cover image with a PNG or JPEG, from a direct imageUrl OR a local filePath under DISCORD_MCP_FILE_ROOT. Prefer imageUrl: a poster already posted to Discord has a CDN URL, and using it needs no local file at all. Discord displays covers at 5:2 (800x320 recommended) and crops anything else, so crop to 5:2 yourself to control what is kept. Max 5MB. Animation is never shown, so an animated GIF is refused and an animated PNG plays as a still.")
    public String setScheduledEventImage(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the scheduled event") String eventId,
            @ToolParam(description = "Direct URL to a PNG or JPEG, e.g. an attachment's CDN link. Needs no filesystem access.", required = false) String imageUrl,
            @ToolParam(description = "Path to a local PNG or JPEG, which must resolve to a file under DISCORD_MCP_FILE_ROOT", required = false) String filePath) {
        // isBlank, not isEmpty: "   " is not a supplied argument, and treating it as one
        // fails later with "File not found at filePath:    ". Matches requireCoverFileRoot below.
        boolean hasUrl = imageUrl != null && !imageUrl.isBlank();
        boolean hasPath = filePath != null && !filePath.isBlank();
        if (hasUrl == hasPath) {
            throw new IllegalArgumentException(hasUrl
                    ? "Supply imageUrl or filePath, not both."
                    : "Supply either imageUrl (a direct link, no filesystem access needed) or "
                    + "filePath (a local file under DISCORD_MCP_FILE_ROOT).");
        }

        // Cheap checks first, matching download_attachment: it resolves its root before spending
        // any network call so a misconfigured root fails immediately rather than after 50 MB.
        // Same shape here — a mistyped eventId should not cost a 5 MB transfer. Both are cache
        // reads, except on a cache miss, where getEventForCover spends one GET to tell "does not
        // exist" from "not here yet". It does not weaken the guards below: they are what confine
        // the source, and nothing here can route around them.
        Guild guild = getGuild(guildId);
        ScheduledEvent event = getEventForCover(guild, eventId);
        // Resolved before the read for the same reason, so an unset or bad root is reported
        // without having opened anything.
        Path root = hasPath
                ? LocalFileGuard.resolveRoot(requireCoverFileRoot(), "DISCORD_MCP_FILE_ROOT")
                : null;

        String source;
        byte[] bytes;
        try {
            if (hasUrl) {
                // The shared SSRF guard, same as send_file and create_emoji: https only, public
                // host, no redirect following, bounded read. This is the path that needs no
                // filesystem grant, which is why it is offered first — a cover almost always
                // starts as an image already posted to Discord, and requiring it to be staged on
                // disk first is what pushes operators into pointing FILE_ROOT at their download
                // directory.
                bytes = RemoteFetchGuard.fetch(imageUrl, MAX_COVER_BYTES, "cover image");
                source = imageUrl;
            } else {
                LocalFileGuard.ConfinedPath path =
                        LocalFileGuard.resolveWithinRoot(filePath, root, "filePath", "upload");
                bytes = LocalFileGuard.readBounded(path, MAX_COVER_BYTES, "cover image");
                source = path.path().getFileName().toString();
            }
        } catch (LocalFileGuard.TooLargeException | RemoteFetchGuard.TooLargeException e) {
            // The limit alone leaves the caller stuck: an oversized master is the ordinary input
            // here, and the fix is the same crop the display shape needs anyway.
            //
            // Both branches are made to read the same way. RemoteFetchGuard's message is generic
            // ("cover image exceeds the maximum allowed size" — lowercase, no period, no number)
            // while LocalFileGuard's names the limit, so a URL and a path failing for identical
            // reasons produced visibly different errors. The size is stated here either way.
            throw new IllegalArgumentException("Cover image exceeds the "
                    + FileSizes.format(MAX_COVER_BYTES) + " limit."
                    + " Crop it to 5:2 and scale it down first — a cover is displayed at 800x320,"
                    + " so a full-resolution master is both too large and the wrong shape.", e);
        }
        Icon.IconType type = coverType(bytes, hasUrl ? "imageUrl" : "filePath");

        // From the live event, not event.getImageUrl(). JDA's cached entity keeps whatever hash it
        // last saw, so a cover changed out of band — by a human in the Discord UI, most likely —
        // would be reported as the previous image.
        //
        // This is the third request a successful call makes, and it is kept for the failure path
        // rather than for the "Was:" line it also feeds. Without it, a thrown write can only say
        // what the event has now; with it, describeOutcome can separate "still the old cover" from
        // "something moved" from "the cover was removed" — the difference between a caller who can
        // act and one who has to go and look. Best-effort: failing to read it is no reason to
        // refuse to set a new cover.
        String before = null;
        boolean beforeKnown = false;
        try {
            before = coverUrlOf(fetchRaw(guild.getId(), event.getId()), event.getId());
            beforeKnown = true;
        } catch (RuntimeException ignored) {
            // Reported as unread below rather than as absent. "no cover image" is a claim about the
            // event, and a failed read cannot support it.
        }

        // Built before the try, whose catch reports "Sent 1.2 MB of PNG from x" and reads the
        // event back. Icon.from only fails on null arguments, unreachable here — but inside the
        // try it would produce a message claiming bytes left the process that never did.
        Icon icon = Icon.from(bytes, type);
        try {
            event.getManager().setImage(icon).complete();
        } catch (RuntimeException e) {
            // Same rule as patchRecurrence above: a thrown request does not prove the change did
            // not happen, since a lost response after Discord applied the image looks identical
            // from here. Read the event back and report what is true rather than asserting an
            // outcome this cannot know.
            //
            // The cause is deliberately not characterised. A size rejection, a missing
            // MANAGE_EVENTS, and a completed or cancelled event all arrive as the same exception,
            // and naming one of them points the caller at the wrong fix. The size and format are
            // context, not a diagnosis.
            String outcome;
            try {
                outcome = describeOutcome(
                        coverUrlOf(fetchRaw(guild.getId(), event.getId()), event.getId()),
                        before, beforeKnown);
            } catch (RuntimeException unverifiable) {
                outcome = " Could not read the event back, so whether the cover was applied is"
                        + " unknown — check the event before retrying.";
            }
            throw new IllegalArgumentException("Setting the cover image failed" + reason(e)
                    + ". Sent " + FileSizes.format(bytes.length) + " of " + type.name() + " from "
                    + source + "." + outcome, e);
        }

        // Re-read rather than trusting the write. The manager reports success on an accepted
        // request, but the entity in memory keeps the old hash, so reporting from it would print
        // the previous cover as though it were the new one — a success message that shows the
        // wrong image is worse than no message. This is also the only confirmation available that
        // Discord kept what it was given.
        String after;
        try {
            after = coverUrlOf(fetchRaw(guild.getId(), event.getId()), event.getId());
        } catch (RuntimeException e) {
            return "Set the cover image on " + event.getName() + " (ID: " + event.getId() + ") from "
                    + source + ", but could not read the event back to confirm it"
                    + reason(e) + ". Check the event before uploading again.";
        }

        StringBuilder result = new StringBuilder("Set the cover image on ")
                .append(event.getName()).append(" (ID: ").append(event.getId()).append(")")
                .append("\n  • From: ").append(source)
                .append(" (").append(type.name()).append(", ").append(FileSizes.format(bytes.length)).append(")")
                .append("\n  • Was: ")
                .append(!beforeKnown ? "could not be read" : before == null ? "no cover image" : before)
                // Absence at read-back does not establish a cause. The write was accepted, so the
                // upload may well have landed and then been removed or replaced by someone else
                // between the two calls. "Discord did not keep it" named one explanation and would
                // prompt a retry that could overwrite a deliberate change — the same
                // over-attribution the failure path above stopped making.
                .append("\n  • Now: ")
                .append(after == null ? "no cover image — check the event before re-uploading" : after);
        if (beforeKnown && after != null && after.equals(before)) {
            // An unchanged hash is worth saying out loud: the likeliest cause is uploading the
            // file that was already there, and the call would otherwise read as a successful
            // change. Whether Discord derives the hash from content or mints one per upload is
            // not documented, so this may simply never fire — a branch that stays quiet, not a
            // wrong answer, and the reported before/after URLs are correct either way.
            result.append("\n  • Unchanged: the event's cover hash did not move, so this is"
                    + " the image it already had.");
        }
        return result.toString();
    }

    /**
     * What a failed cover write actually left behind, given the event read back afterwards.
     *
     * <p>Separated from the call so it can be tested: every branch here is a claim about whether a
     * write took effect, this is the block most likely to be reworded into saying the wrong one,
     * and exercising it in place would mean mocking JDA's request construction.
     *
     * @param now         the cover the event has now, or null if it has none
     * @param before      the cover it had before the write, or null if it had none
     * @param beforeKnown whether {@code before} was actually read, as opposed to unavailable
     */
    static String describeOutcome(String now, String before, boolean beforeKnown) {
        if (now == null) {
            // Losing a cover is a larger change than swapping one, so it must not share the
            // wording used for an event that never had a cover at all. Claimable only when the
            // previous one was actually read; without that there is nothing to compare against.
            return beforeKnown && before != null
                    ? " The event's cover was REMOVED during this call — it had " + before
                    + " before and has none now. Check the event rather than re-uploading blind."
                    : " The event currently has no cover image.";
        }
        if (!beforeKnown) {
            return " The event currently has cover " + now + ", but the previous one could not be"
                    + " read, so whether this call changed it is unknown.";
        }
        if (now.equals(before)) {
            return " The event still has the cover it had before this call.";
        }
        // The cover moved. The tempting reading is "the write landed and its response was lost",
        // and that is often what happened — but it is not the only thing that produces this. A
        // genuine rejection followed by someone else editing the cover between the two reads looks
        // identical from here, and nothing available correlates the current image with the bytes
        // this call sent. Report the observation and let the caller look, rather than asserting
        // authorship of a change that may not be this one's.
        return " The event's cover CHANGED during this call and is now " + now
                + ". That may mean the request applied and only its response was lost, or that"
                + " something else changed it in the meantime — this cannot tell them apart, so"
                + " check the event rather than re-uploading blind.";
    }


    /**
     * The one-line cover summary for a listing.
     *
     * <p>Stated once rather than per event: a missing cover is worth surfacing, since it is
     * otherwise invisible and a listing that only ever prints URLs cannot distinguish "has no
     * cover" from "not reported" — but it does not need a line each on a listing with no result
     * cap.
     *
     * <p>Every count is separate because each supports a different claim, and merging any two
     * makes the line assert something it does not know. Events come from JDA's cache and covers
     * from a live REST read, so the two can disagree in both directions and an entry can also be
     * returned without being parseable. Saying "not in the live read" about an event Discord did
     * return, or leaving an event Discord returned out of the incomplete-list count, are each a
     * quiet version of the mistake this whole structure exists to prevent.
     *
     * <p>Extracted and tested for the same reason as {@code describeOutcome}: every clause is a
     * claim about what the reader is looking at, and testing it in place would mean mocking JDA's
     * request construction.
     *
     * @param described  listed events the live response described in full
     * @param coverless  how many of those have no cover
     * @param unreadable listed events Discord returned but whose details would not parse
     * @param absent     listed events Discord did not return at all
     * @param unlisted   events Discord returned that are not in the listing
     * @param unidentifiable entries Discord returned with no usable id, which cannot be matched
     *                       to any listed event in either direction
     * @param recurrenceUnreadable events whose recurrence would not parse, so a missing
     *                             "Recurs:" line below means unknown rather than absent
     * @param rawKnown   whether the live read succeeded at all
     */
    static String coverCaveat(int described, int coverless, int unreadable, int absent,
                              int unlisted, int unidentifiable, int recurrenceUnreadable,
                              boolean rawKnown) {
        if (!rawKnown) {
            return "\n(Recurrence and cover images could not be read, so no event below is marked as"
                    + " recurring or as having a cover even if it is.)";
        }
        StringBuilder sb = new StringBuilder();
        if (coverless > 0) {
            // Covers are rare, so "N of N" is the ordinary case and its arithmetic says nothing.
            // Kept rather than suppressed: this line is why a stale or absent cover stops being
            // invisible, which is the whole reason the listing reads them.
            // The absolute phrasing is only supportable when the live read described every listed
            // event. With 1 of 3 described and coverless, "no event here has a cover image" makes
            // a claim about the two it never saw — beside a clause that calls those two unknown.
            if (coverless == described && unreadable == 0 && absent == 0 && unidentifiable == 0) {
                sb.append("no event here has a cover image");
            } else {
                // The noun counts `described` and the verb agrees with `coverless`: "1 of 3
                // events has no cover image". Taking both from one number gets one of them wrong,
                // and taking both from `described` is what produced "1 of 3 events have". The
                // arm for described == 1 that used to be here was unreachable anyway — reaching
                // it needs coverless < described == 1, so coverless == 0, which skips the block.
                sb.append(coverless).append(" of ").append(described).append(" events")
                        .append(coverless == 1 ? " has" : " have").append(" no cover image");
            }
        }
        if (unreadable > 0) {
            join(sb).append(unreadable).append(unreadable == 1 ? " event was" : " events were")
                    .append(" returned but could not be read, so ")
                    .append(unreadable == 1 ? "its cover is" : "their covers are").append(" unknown");
        }
        if (absent > 0) {
            join(sb).append(absent).append(absent == 1 ? " event was" : " events were")
                    // Hedged when an entry came back with no usable id, because that entry could
                    // be one of these: nothing matches it to a listed event, so "not in the live
                    // read" stops being knowable. Saying it flatly would be the same unsupported
                    // claim these counters were split apart to avoid, made about the one case
                    // where the split cannot help.
                    .append(unidentifiable > 0
                            ? " not matched to anything in the live read, though the unreadable"
                            + " entries below may be among them, so "
                            : " not in the live read, so ")
                    .append(absent == 1 ? "its cover is" : "their covers are").append(" unknown");
        }
        if (unlisted > 0) {
            join(sb).append("Discord returned ").append(unlisted).append(" event")
                    .append(unlisted == 1 ? "" : "s").append(" not in this list, so the list is")
                    .append(" incomplete — the cache has not caught up");
        }
        if (recurrenceUnreadable > 0) {
            join(sb).append(recurrenceUnreadable)
                    .append(recurrenceUnreadable == 1 ? " event's recurrence" : " events' recurrences")
                    .append(" could not be read, so ")
                    .append(recurrenceUnreadable == 1 ? "it shows" : "they show")
                    .append(" no schedule below even if ")
                    .append(recurrenceUnreadable == 1 ? "it recurs" : "they recur");
        }
        if (unidentifiable > 0) {
            // Its own clause because it belongs to no event. Folding it into `unreadable` would
            // name an event that cannot be named, and letting it fall through to `absent` would
            // blame the cache for a malformed response.
            join(sb).append(unidentifiable).append(unidentifiable == 1 ? " entry" : " entries")
                    .append(" could not be read at all, so ")
                    .append(unidentifiable == 1 ? "it is" : "they are")
                    .append(" not counted above either way");
        }
        return sb.length() == 0 ? "" : "\n(" + sb + ".)";
    }

    private static StringBuilder join(StringBuilder sb) {
        return sb.length() == 0 ? sb : sb.append("; ");
    }

    /**
     * An exception's message as a trailing clause, or nothing when it has none.
     *
     * <p>{@code getMessage()} can be null, and concatenating it renders the word "null" into a
     * sentence. {@code editScheduledEvent} already guards this, and a null message once made a
     * whole note vanish elsewhere in this file.
     */
    private static String reason(RuntimeException e) {
        return e.getMessage() == null ? "" : ": " + e.getMessage();
    }

    /** The cover image URL from a raw event object, or null if it has no cover. */
    // Package-private for the same reason as resolveEndTime and coverType: testable without a
    // live event.
    static String coverUrlOf(DataObject raw, String eventId) {
        String hash = raw.getString("image", null);
        return hash == null ? null : coverUrl(eventId, hash);
    }

    private static String coverUrl(String eventId, String hash) {
        // JDA's own template, so the CDN host and path stay in one place rather than being spelled
        // out here — and its extension rule too, rather than borrowing the template and then
        // diverging on the one part of it that is conditional. An "a_" hash means animated, which
        // a cover set through this tool can never be; but a hash this did not write, or a
        // Discord-side change, would otherwise produce a URL that 404s everywhere it is printed.
        return String.format(ScheduledEvent.IMAGE_URL, eventId, hash,
                hash.startsWith("a_") ? "gif" : "png");
    }

    /**
     * Identify the format from the bytes, not the file name.
     *
     * <p>An extension is caller-supplied text. Discord rejects a mislabelled body, and JDA's
     * {@code IconType.fromExtension} would happily build a PNG icon around a JPEG, producing a
     * Discord-side error that blames the request rather than the file.
     */
    // Package-private so the format cases can be tested without a live event, matching
    // resolveEndTime above. paramName because this runs on both branches: telling a caller who
    // passed a WebP CDN link that "filePath is not a PNG" names a parameter they did not use, and
    // Discord's own media proxy serves WebP, so that is an ordinary mistake rather than a rare one.
    static Icon.IconType coverType(byte[] bytes, String paramName) {
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && (bytes[4] & 0xFF) == 0x0D && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A && (bytes[7] & 0xFF) == 0x0A) {
            return Icon.IconType.PNG;
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return Icon.IconType.JPEG;
        }
        // GIF is called out by name because it is the plausible mistake: Discord accepts animated
        // avatars and banners elsewhere, but not on scheduled event covers.
        if (bytes.length >= 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            throw new IllegalArgumentException(
                    "Scheduled event covers cannot be GIFs — Discord does not animate them. "
                            + "Supply a PNG or JPEG.");
        }
        throw new IllegalArgumentException(paramName
                + " is not a PNG or JPEG. Discord accepts only those for event covers.");
    }

    /**
     * Like {@link #getEvent}, but does not report a cache miss as a missing event.
     *
     * <p>{@code getScheduledEventById} reads JDA's cache, which is filled from the gateway. An
     * event created seconds earlier — by {@code create_guild_scheduled_event}, or by a human in
     * the Discord UI — exists at Discord before it exists here, and "not found by eventId" sends
     * the caller to check an id that is correct. The listing already reports this state from the
     * other side, as an event Discord returned that the cache does not hold.
     *
     * <p>A live read distinguishes the two. It does not fix the underlying limitation: the write
     * below needs a cached entity for its manager, so a valid-but-uncached event still cannot be
     * given a cover. Routing the write through {@code patchRaw} with {@code Icon#getEncoding}
     * would remove the cache from this path entirely and drop a request besides; that is a change
     * to how the write works and belongs in its own review, not appended to this one.
     */
    private ScheduledEvent getEventForCover(Guild guild, String eventId) {
        if (eventId == null || eventId.isEmpty()) {
            throw new IllegalArgumentException("eventId cannot be null");
        }
        ScheduledEvent event = guild.getScheduledEventById(eventId);
        if (event != null) {
            return event;
        }
        try {
            fetchRaw(guild.getId(), eventId);
        } catch (ErrorResponseException discordSaidNo) {
            // Only Discord saying "no such event" establishes that. A 500, a timeout or an
            // exhausted rate limit says nothing about whether the event exists, and reporting
            // those as "not found" sends the caller to check an id that may be correct — the
            // same over-attribution describeOutcome refuses to make two hundred lines up.
            if (discordSaidNo.getErrorResponse() == ErrorResponse.UNKNOWN_SCHEDULED_EVENT) {
                throw new IllegalArgumentException("Scheduled event not found by eventId");
            }
            throw new IllegalArgumentException("Could not confirm whether that event exists"
                    + reason(discordSaidNo) + ". It is not in this server's cache, which is normal"
                    + " for an event created moments ago. Retry before assuming the id is wrong.");
        } catch (RuntimeException unreachable) {
            throw new IllegalArgumentException("Could not reach Discord to confirm whether that"
                    + " event exists" + reason(unreachable) + ". It is not in this server's cache,"
                    + " which is normal for an event created moments ago. Retry before assuming"
                    + " the id is wrong.");
        }
        throw new IllegalArgumentException(
                "That event exists at Discord but has not reached this server's cache yet, so its "
                        + "cover cannot be set. This usually clears within seconds of the event "
                        + "being created — retry shortly.");
    }

    /** The upload root, or a refusal that points at the parameter which needs no filesystem. */
    private String requireCoverFileRoot() {
        if (coverFileRoot == null || coverFileRoot.isBlank()) {
            throw new IllegalArgumentException(
                    "Local paths are disabled. Set DISCORD_MCP_FILE_ROOT to the directory this "
                            + "server may read uploads from, or supply imageUrl instead — that "
                            + "needs no filesystem access.");
        }
        return coverFileRoot;
    }

    @Tool(name = "delete_guild_scheduled_event", description = "Permanently delete a scheduled event")
    public String deleteScheduledEvent(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the scheduled event") String eventId) {

        Guild guild = getGuild(guildId);
        ScheduledEvent event = getEvent(guild, eventId);
        String eventName = event.getName();
        event.delete().complete();
        return "Successfully deleted scheduled event: " + eventName + " (ID: " + eventId + ")";
    }

    @Tool(name = "list_guild_scheduled_events", description = "List all active and scheduled events on the server")
    public String listScheduledEvents(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Whether to include interested user count (default true)", required = false) String withUserCount) {

        Guild guild = getGuild(guildId);
        List<ScheduledEvent> events = guild.getScheduledEvents();

        if (events.isEmpty()) {
            return "No scheduled events found on this server.";
        }

        boolean includeUserCount = withUserCount == null || withUserCount.isEmpty() || Boolean.parseBoolean(withUserCount);

        // One raw list call so recurrence is visible here. Without it a weekly class and a one-off
        // look identical, which is how a recurring event gets edited as though it were not one.
        //
        // The same response also carries the cover image hash, which is read here rather than from
        // ScheduledEvent.getImageUrl() for two reasons: it costs nothing extra, and it is live. A
        // cover changed out of band is exactly the case this listing needs to be right about, and
        // that is the case where JDA's cached entity is stale.
        java.util.Map<String, DataObject> rules = new java.util.HashMap<>();
        java.util.Map<String, String> covers = new java.util.HashMap<>();
        java.util.Set<String> described = new java.util.HashSet<>();
        // Every id Discord returned, whether or not its details parsed. Kept apart from
        // `described` because "not returned" and "returned but unreadable" are different facts
        // and the caveat states them differently — collapsing them made a returned-but-unreadable
        // event report as a cache lag that had not happened.
        java.util.Set<String> returned = new java.util.HashSet<>();
        // Entries with no usable id. They cannot be attributed to any listed event, so they
        // cannot be counted as unreadable-but-identified — they get their own line or none.
        int unidentifiable = 0;
        // Ids whose recurrence_rule would not parse. A set rather than a counter because the
        // clause it feeds says the event "shows no schedule below" — true only of events that
        // have a row below. `described`, `unreadable` and `absent` are all narrowed to the listed
        // events for the same reason; counting this one raw would describe an event Discord
        // returned but the cache does not hold as showing nothing below, when it shows nothing
        // at all.
        java.util.Set<String> recurrenceFailed = new java.util.HashSet<>();
        boolean rawKnown = false;
        try {
            Route.CompiledRoute route = Route.custom(Method.GET, "guilds/{guild_id}/scheduled-events")
                    .compile(guild.getId());
            var raw = new RestActionImpl<net.dv8tion.jda.api.utils.data.DataArray>(jda, route,
                    (response, request) -> response.getArray()).complete();
            for (int i = 0; i < raw.length(); i++) {
                // The element AND its id are read inside the guard. Pulling the id out below it
                // left getString("id", null) exposed: the default covers an absent key, not a
                // non-string one, which throws and reaches the outer catch — discarding every
                // cover and recurrence already parsed and reporting the whole live read as
                // failed. That is the cost this per-entry guard exists to avoid, one line outside
                // the guard.
                DataObject o;
                String id;
                try {
                    o = raw.getObject(i);
                    id = o.getString("id", null);
                } catch (RuntimeException malformed) {
                    unidentifiable++;
                    continue;
                }
                if (id == null || id.isBlank()) {
                    // Counted, not silently dropped. Discord did return this event; without a
                    // usable id there is no way to say which, so a listed copy of it would
                    // otherwise fall into "not in the live read" — the mislabelling the rest of
                    // this exists to avoid — and nothing would mention it at all. Blank as well as
                    // null: a blank id matches no listed event, so it would otherwise enter
                    // `returned` and be counted as a phantom event missing from the list.
                    unidentifiable++;
                    continue;
                }
                // Recorded before the details are parsed: Discord did return this event, whatever
                // happens to the rest of it.
                returned.add(id);
                // Parsed independently, and tracked independently. Separate try blocks stop one
                // malformed field discarding the other; separate flags stop one field's success
                // standing in as proof the other was read. OR-ing them into a single "readable"
                // did the second thing: a malformed image beside a good recurrence rule still
                // entered `described`, so the summary counted the event as having no cover — a
                // positive claim drawn from a read that failed, which is exactly what the
                // described/returned split exists to prevent.
                DataObject rule = null;
                String cover = null;
                boolean recurrenceRead = false;
                boolean coverRead = false;
                try {
                    rule = recurrenceOf(o);
                    recurrenceRead = true;
                } catch (RuntimeException malformed) {
                    // Recurrence is lost for this event; its cover may still be readable.
                }
                try {
                    cover = coverUrlOf(o, id);
                    coverRead = true;
                } catch (RuntimeException malformed) {
                    // Likewise in reverse.
                }
                // Recorded before the cover gate, not after. Separate try blocks and separate
                // flags stopped a malformed field discarding its neighbour's value and vouching
                // for its neighbour's success; this is the third face of the same invariant, and
                // it was still broken: `continue` on an unreadable cover skipped rules.put, so a
                // perfectly good recurrence vanished and the caveat blamed only the cover. That
                // is the silent "does not recur" this counter exists to prevent, reached from the
                // other side. Each field's knowledge is committed on its own terms.
                if (rule != null) {
                    rules.put(id, rule);
                }
                if (!recurrenceRead) {
                    // Otherwise this event renders with no "Recurs:" line and nothing to say why.
                    recurrenceFailed.add(id);
                }
                if (!coverRead) continue;
                // Every event the live response described, whether or not it has a cover. The
                // events being listed come from JDA's cache, so one can be present there and
                // absent here — deleted out of band, or a stale cache. Keying "none" off this set
                // rather than off a missing map entry keeps that case from turning a response
                // that said nothing about an event into a claim that it has no cover.
                described.add(id);
                // Same helper the write path uses, so "read the cover from a raw event" has one
                // spelling rather than two that can drift apart.
                if (cover != null) {
                    covers.put(id, cover);
                }
            }
            rawKnown = true;
        } catch (RuntimeException e) {
            // Recurrence and cover detail are enhancements to this listing, not its purpose, so
            // losing them must not turn a working list call into a failure. They must not silently
            // read as "nothing recurs" and "no covers" either — that is indistinguishable from the
            // real thing, and the whole point of reporting a missing cover is that its absence is
            // information.
            rules.clear();
            covers.clear();
            described.clear();
            returned.clear();
            unidentifiable = 0;
            recurrenceFailed.clear();
        }

        int describedCount = (int) events.stream().filter(e -> described.contains(e.getId())).count();
        int coverlessCount = (int) events.stream()
                .filter(e -> described.contains(e.getId()) && !covers.containsKey(e.getId()))
                .count();
        // Listed events Discord returned but could not be read, and listed events it did not
        // return at all. Different facts, counted separately so neither is described as the other.
        int unreadableCount = (int) events.stream()
                .filter(e -> returned.contains(e.getId()) && !described.contains(e.getId()))
                .count();
        int absentCount = (int) events.stream().filter(e -> !returned.contains(e.getId())).count();
        int unlistedCount = returned.size()
                - (int) events.stream().filter(e -> returned.contains(e.getId())).count();
        int recurrenceUnreadable = (int) events.stream()
                .filter(e -> recurrenceFailed.contains(e.getId())).count();
        String caveat = coverCaveat(describedCount, coverlessCount, unreadableCount,
                absentCount, unlistedCount, unidentifiable, recurrenceUnreadable, rawKnown);
        return "Retrieved " + events.size() + " scheduled events:" + caveat + "\n" +
                events.stream()
                        .map(e -> {
                            StringBuilder sb = new StringBuilder();
                            sb.append("- **").append(e.getName()).append("** (ID: ").append(e.getId()).append(")\n");
                            sb.append("  • Type: ").append(e.getType()).append(" | Status: ").append(e.getStatus()).append("\n");
                            sb.append("  • Start: ").append(e.getStartTime());
                            if (e.getEndTime() != null) sb.append(" | End: ").append(e.getEndTime());
                            DataObject rule = rules.get(e.getId());
                            if (rule != null) sb.append("\n  • Recurs: ").append(RecurrenceRule.describe(rule));
                            // Only the URL, and only when there is one. A per-event "none" would
                            // be a line of nothing per coverless event on a listing with no result
                            // cap; the header count carries that once instead. The recurrence line
                            // above omits itself for the same reason.
                            String cover = covers.get(e.getId());
                            if (cover != null) sb.append("\n  • Cover image: ").append(cover);
                            if (includeUserCount) sb.append("\n  • Interested: ").append(e.getInterestedUserCount()).append(" users");
                            return sb.toString();
                        })
                        .collect(Collectors.joining("\n"));
    }

    @Tool(name = "get_guild_scheduled_event_users", description = "Get list of users interested in a scheduled event")
    public String getScheduledEventUsers(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the scheduled event") String eventId,
            @ToolParam(description = "Max number of users to return (default 100)", required = false) String limit,
            @ToolParam(description = "Whether to include full member data with roles (default true)", required = false) String withMember) {

        Guild guild = getGuild(guildId);
        ScheduledEvent event = getEvent(guild, eventId);

        int maxResults = (limit != null && !limit.isEmpty()) ? Integer.parseInt(limit) : 100;
        boolean includeMember = withMember == null || withMember.isEmpty() || Boolean.parseBoolean(withMember);

        List<Member> members = event.retrieveInterestedMembers()
                .stream()
                .limit(maxResults)
                .toList();

        if (members.isEmpty()) {
            return "No interested users found for event: " + event.getName();
        }

        return "Retrieved " + members.size() + " interested users for event **" + event.getName() + "**:\n" +
                members.stream()
                        .map(m -> {
                            StringBuilder sb = new StringBuilder();
                            sb.append("- **").append(m.getUser().getName()).append("** (ID: ").append(m.getId()).append(")");
                            if (includeMember) {
                                String roles = m.getRoles().stream()
                                        .map(r -> r.getName() + " (" + r.getId() + ")")
                                        .collect(Collectors.joining(", "));
                                if (!roles.isEmpty()) sb.append("\n  • Roles: ").append(roles);
                                if (m.getNickname() != null) sb.append("\n  • Nickname: ").append(m.getNickname());
                            }
                            return sb.toString();
                        })
                        .collect(Collectors.joining("\n"));
    }
}
