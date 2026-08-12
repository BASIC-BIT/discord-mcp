package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.ParsingException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.Method;
import net.dv8tion.jda.api.requests.Route;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;
import net.dv8tion.jda.internal.requests.RestActionImpl;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * Where {@code download_attachment} writes, read here only to refuse reading covers out of it.
     * See {@link #coverRoot()}. Package-private for the same reason as {@link #coverFileRoot}.
     */
    @Value("${DISCORD_MCP_DOWNLOAD_ROOT:}")
    String downloadRoot;

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
                .compile(requireSnowflake(guildId, "guildId"), requireSnowflake(eventId, "eventId"));
        return new RestActionImpl<DataObject>(jda, route,
                (response, request) -> response.getObject()).complete();
    }

    /** The guild's scheduled events as raw JSON, for the fields JDA's entities do not carry. */
    private DataArray fetchRawList(String guildId) {
        Route.CompiledRoute route = Route.custom(Method.GET, "guilds/{guild_id}/scheduled-events")
                .compile(requireSnowflake(guildId, "guildId"));
        return new RestActionImpl<DataArray>(jda, route,
                (response, request) -> response.getArray()).complete();
    }

    private DataObject patchRaw(String guildId, String eventId, DataObject body) {
        Route.CompiledRoute route = Route.custom(Method.PATCH, "guilds/{guild_id}/scheduled-events/{event_id}")
                .compile(requireSnowflake(guildId, "guildId"), requireSnowflake(eventId, "eventId"));
        return new RestActionImpl<DataObject>(jda, route, body,
                (response, request) -> response.getObject()).complete();
    }

    /**
     * Refuse anything that is not a snowflake before it becomes part of a route.
     *
     * <p>{@code Route#compile} substitutes placeholders textually, with no percent-encoding, and
     * the result is concatenated onto the API prefix and handed to OkHttp, which canonicalises dot
     * segments. A value containing {@code /}, {@code ..}, {@code ?} or {@code #} therefore chooses
     * which endpoint the bot token is spent on rather than which event is addressed.
     *
     * <p>Every tool that goes through the cache gets this for free from
     * {@code MiscUtil.parseSnowflake} inside {@code getScheduledEventById}. These routes bypass the
     * cache deliberately — an event created seconds ago is not in it — so the check has to be here
     * rather than inherited. Applied at the route rather than at each tool so a future caller
     * cannot reintroduce the gap by forgetting it.
     */
    private static String requireSnowflake(String id, String paramName) {
        if (!isSnowflake(id)) {
            // Both halves named: "digits only" alone tells the caller of a 20-digit id that its
            // digits are the problem, and the obvious next move is to send it again.
            throw new IllegalArgumentException(paramName
                    + " must be a Discord snowflake: ASCII digits, within 64-bit range");
        }
        return id;
    }

    /**
     * Whether a string is a Discord id: ASCII digits, and a value a snowflake can hold.
     *
     * <p>{@code Character.isDigit} is not this check — it is true for every Unicode decimal digit,
     * so Arabic-Indic numerals would pass a test whose message says digits. Neither is
     * {@code MiscUtil.parseSnowflake} on its own, which accepts a leading sign. Nothing here is a
     * traversal defence, {@code /} and {@code .} are excluded either way; it is about the promise
     * the name makes being the promise the code keeps.
     *
     * <p>The range matters for the same reason: 20 digits is a legal length and still overflows,
     * and JDA's own parse of such a value throws rather than addressing the event the caller meant.
     *
     * <p>Package-private because {@link LiveEventDetails} needs the predicate rather than the
     * refusal — an id that is not a snowflake cannot match a listed event, whose ids come from JDA
     * entities, so recording one would manufacture a "Discord has an event this list does not"
     * claim out of a malformed field.
     */
    static boolean isSnowflake(String id) {
        if (id == null || id.isEmpty() || !id.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        try {
            Long.parseUnsignedLong(id);
            return true;
        } catch (NumberFormatException tooLargeForASnowflake) {
            return false;
        }
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
                DataObject rule = RecurrenceRule.of(after);
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
        List<String> parts = new ArrayList<>();
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
        DataObject existingRecurrence = RecurrenceRule.of(raw);

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
            // A recurring event's series is anchored by
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
     * <p>Unverified for recurring events. REVIEW.md records that editing one changes a single
     * occurrence while the series re-anchors, and this PATCHes the same endpoint — so if that
     * applies to {@code image} too, the read-back would show a hash that looks right for a cover
     * that landed somewhere the caller did not mean. Nobody has checked; saying so is cheaper
     * than the reader assuming it was. Said in the {@code @Tool} description as well as here,
     * because a caveat only a maintainer reads cannot act on anything: the description is the
     * only text the model sees, and the success message otherwise reads as confirmation.
     *
     * <p>Takes {@code imageUrl} as well as {@code filePath} for a related reason. With only a
     * local path, the ordinary job — put a poster that is already in Discord onto an event —
     * requires a filesystem grant, and the shortest route to one is pointing
     * {@code DISCORD_MCP_FILE_ROOT} at the download directory, which is exactly the widening the
     * README argues against. A tool whose safe configuration is the inconvenient one gets
     * configured unsafely.
     */
    @Tool(name = "set_guild_scheduled_event_image", description = "Replace a scheduled event's cover image with a PNG or JPEG, from a direct imageUrl OR a local filePath under DISCORD_MCP_FILE_ROOT. Prefer imageUrl: a poster already posted to Discord has a CDN URL, and using it needs no local file at all. Discord displays covers at 5:2 (800x320 recommended) and crops anything else, so crop to 5:2 yourself to control what is kept. Max 5MB. Animation is never shown, so an animated GIF is refused and an animated PNG plays as a still. On a recurring event the effect is unverified: editing one is known to change a single occurrence, so check the series after setting a cover on it.")
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
        //
        // The root goes first of all, because it is the only check here that costs nothing at
        // all: a filePath call on a deployment with no upload root should refuse with "Local
        // paths are disabled" without spending a request to get there.
        LocalFileGuard.Root root = hasPath ? coverRoot() : null;
        // Then the ids. The event itself is read further down, after the source; the trade that
        // ordering makes is set out where the source is read.
        //
        // No cached entity is needed: the write below goes through patchRaw, the same raw route
        // this file already uses for every other scheduled-event field. Using JDA's manager here
        // was the deviation, and it dragged in a cache dependency that made the flow the tool
        // description recommends — create an event, then cover it — fail on first try whenever the
        // gateway had not caught up.
        // getGuild, not just a blank check: the gateway-lag argument for bypassing the cache is
        // about which events exist, not about which guilds the bot is in, and the cache is
        // authoritative for the latter. A mistyped guildId is refused here for free rather than
        // after a fetch and a round trip.
        Guild guild = getGuild(guildId);
        String resolvedGuild = guild.getId();
        // The same check the raw routes below make, run here so it costs nothing: a bad id refused
        // now is refused before up to 8 MB is fetched or read from disk, rather than after.
        requireSnowflake(eventId, "eventId");

        // The source is read before anything establishes the event exists, and what that buys is
        // narrow: one saved Discord request when the source is wrong — outside the root, aimed at
        // a link-local address, 12 MB, a WebP. It is not what makes those refusals safe.
        // RemoteFetchGuard rejects an internal address wherever it runs and LocalFileGuard
        // confines wherever it runs; neither depends on this ordering.
        //
        // What it costs is that a syntactically valid eventId naming no event still spends the
        // fetch. Kept anyway: a caller can name a real event as cheaply as a fake one, so the
        // ordering bounds bandwidth rather than exposure, and the common mistake here is the
        // wrong file — which this refuses without asking Discord anything.
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

        // One read, doing two jobs: it establishes the event exists — authoritatively, from
        // Discord rather than from a cache that lags — and it captures the cover being replaced,
        // which is what lets a failed write separate "still the old cover" from "something moved"
        // from "the cover was removed".
        //
        // Fail-closed, not best-effort: it is the existence check, so proceeding without it would
        // mean PATCHing an event that may not be there and losing the one answer that says so.
        String eventName;
        Cover before;
        try {
            DataObject current = fetchRaw(resolvedGuild, eventId);
            eventName = current.getString("name", eventId);
            // Cover.of, which absorbs an unreadable image field rather than throwing out of this
            // try. The event was read — that is what this call is for — and a cosmetic field that
            // will not parse must not refuse a write the caller is entitled to make. It costs the
            // "Was:" line, which then says so.
            before = Cover.of(current, eventId);
        } catch (ErrorResponseException discordSaidNo) {
            if (discordSaidNo.getErrorResponse() == ErrorResponse.UNKNOWN_SCHEDULED_EVENT) {
                throw new IllegalArgumentException("Scheduled event not found by eventId");
            }
            throw new IllegalArgumentException("Could not read that event before setting its cover"
                    + reason(discordSaidNo) + ". Nothing was changed.");
        } catch (ParsingException malformed) {
            // Discord answered; the answer would not parse. The listing keeps "returned but
            // unparseable" distinct from "not returned" at some length, and collapsing them here
            // would send the reader to check connectivity for a response that arrived.
            throw new IllegalArgumentException("Discord returned that event but the response could"
                    + " not be read" + reason(malformed) + ". Nothing was changed.");
        } catch (RuntimeException unreachable) {
            throw new IllegalArgumentException("Could not reach Discord to read that event before"
                    + " setting its cover" + reason(unreachable) + ". Nothing was changed.");
        }

        // Outside the try below, whose catch reports "Sent 1.2 MB of PNG from …". Icon.from only
        // fails on null arguments, unreachable here — but inside the try it would produce a
        // message claiming bytes left the process that never did.
        Icon icon = Icon.from(bytes, type);
        DataObject applied;
        try {
            // patchRaw, not the manager. The PATCH response carries the updated event, so the
            // new cover comes back with the write rather than costing a third request — and
            // nothing here needs JDA's cache, which is what made an event created moments ago
            // impossible to cover until the gateway caught up.
            applied = patchRaw(resolvedGuild, eventId,
                    DataObject.empty().put("image", icon.getEncoding()));
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
            //
            // This makes the failure path cost two requests: if the PATCH failed to a rate limit,
            // the read-back goes into the same bucket. Kept anyway — reporting what is true beats
            // guessing, and the alternative is a message that cannot say whether the write took.
            String outcome;
            try {
                outcome = describeOutcome(
                        Cover.of(fetchRaw(resolvedGuild, eventId), eventId), before);
            } catch (RuntimeException unverifiable) {
                outcome = " Could not read the event back, so whether the cover was applied is"
                        + " unknown — check the event before retrying.";
            }
            throw new IllegalArgumentException("Setting the cover image failed" + reason(e)
                    + ". Sent " + FileSizes.format(bytes.length) + " of " + type.name() + " from "
                    + source + "." + outcome, e);
        }

        // Read outside the try above, for the same reason Icon.from sits outside it: the write
        // returned, so the change landed, and a response this cannot read is not a failed write —
        // but the catch's first sentence says one.
        //
        // This came from the PATCH response itself, so it is what Discord stored rather than what
        // was sent — no separate read-back, and no window in which someone else's change could be
        // reported as this call's result.
        return describeCoverWrite(eventName, eventId, source, type, bytes.length, before,
                Cover.of(applied, eventId));
    }

    /**
     * What a response established about an event's cover.
     *
     * <p>One value rather than a URL and a flag beside it, so the sentence naming the state and
     * the line printing the URL cannot disagree. Both sides of the write produce one, because both
     * fail the same three ways: the read that captures what was there, and the response that says
     * what is there now.
     *
     * @param url the cover URL, present only in state {@link State#PRESENT}
     */
    record Cover(String url, State state) {

        enum State {
            /** The response carried a cover URL. That is what Discord has. */
            PRESENT,
            /** The response parsed, and the event has no cover at all. */
            ABSENT,
            /** The cover field would not parse, so it establishes nothing about the event. */
            UNKNOWN
        }

        static Cover of(DataObject raw, String eventId) {
            try {
                String url = coverUrlOf(raw, eventId);
                return url == null ? new Cover(null, State.ABSENT) : new Cover(url, State.PRESENT);
            } catch (ParsingException unreadable) {
                // Only this field failed. Whatever else the response carried was read, so this
                // must not stand in for a response that could not be read at all.
                return new Cover(null, State.UNKNOWN);
            }
        }
    }

    /**
     * What a cover write can be said to have achieved.
     *
     * <p>Separated from the call for the reason {@code describeOutcome} was: every line here is a
     * claim, and no test can reach this through a live PATCH. An unconditional headline gives
     * "Set the cover image on X" above "Now: no cover image" — two answers to one question.
     */
    static String describeCoverWrite(String eventName, String eventId, String source,
                                     Icon.IconType type, int sentBytes, Cover before, Cover after) {
        StringBuilder result = new StringBuilder(after.state() == Cover.State.PRESENT
                        // "Set" only when the response showed the cover Discord stored. Accepted
                        // and confirmed are different facts, and this sentence states the second.
                        ? "Set the cover image on " : "Sent the cover image to ")
                .append(eventName).append(" (ID: ").append(eventId).append(")")
                .append("\n  • From: ").append(source)
                .append(" (").append(type.name()).append(", ").append(FileSizes.format(sentBytes)).append(")")
                .append("\n  • Was: ")
                .append(switch (before.state()) {
                    case PRESENT -> before.url();
                    case ABSENT -> "no cover image";
                    // The event was read; its cover field was not. Saying "no cover image" here
                    // would invent the one fact this call failed to establish.
                    case UNKNOWN -> "unknown — the event's previous cover could not be read";
                })
                .append("\n  • Now: ")
                .append(switch (after.state()) {
                    case PRESENT -> after.url();
                    // Absence does not establish a cause. The write was accepted, so the upload
                    // may well have landed and then been removed or replaced by someone else.
                    // "Discord did not keep it" names one explanation and would prompt a retry
                    // that could overwrite a deliberate change.
                    case ABSENT -> "no cover image — check the event before re-uploading";
                    // Not "no cover image": the response said something this could not read, which
                    // establishes nothing about what the event now has.
                    case UNKNOWN -> "unknown — Discord accepted the write, but its response did"
                            + " not carry a readable cover";
                });
        if (after.state() == Cover.State.PRESENT && after.url().equals(before.url())) {
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
     * @param now    what the read-back established about the cover
     * @param before what the read before the write established about it. The event itself was
     *               read — the call cannot get this far otherwise — but its cover field may not
     *               have been, and then there is nothing to compare against.
     */
    static String describeOutcome(Cover now, Cover before) {
        if (now.state() == Cover.State.UNKNOWN) {
            // The event came back and its cover field did not parse. Nothing about the write
            // follows from that — least of all that it did not take.
            return " The event was read back, but its cover could not be, so whether this call"
                    + " applied is unknown — check the event before retrying.";
        }
        String nowUrl = now.url();
        String beforeUrl = before.url();
        if (before.state() == Cover.State.UNKNOWN) {
            // Every clause below compares against what was there. Without that, the only honest
            // statement is what is there now.
            return nowUrl == null
                    ? " The event has no cover image now. What it had before this call could not"
                    + " be read, so whether that is a change is unknown — check the event."
                    : " The event's cover is now " + nowUrl + ". What it had before this call"
                    + " could not be read, so whether this call set it is unknown — check the"
                    + " event.";
        }
        if (nowUrl == null) {
            // Losing a cover is a larger change than swapping one, so it must not share the
            // wording used for an event that never had a cover at all.
            return beforeUrl != null
                    ? " The event's cover was REMOVED during this call — it had " + beforeUrl
                    + " before and has none now. Check the event rather than re-uploading blind."
                    : " The event currently has no cover image.";
        }
        if (nowUrl.equals(beforeUrl)) {
            return " The event still has the cover it had before this call.";
        }
        if (beforeUrl == null) {
            // Knowable, and asymmetric the other way from a removal: it had none and now has one.
            // "CHANGED" is true but weaker than what the read supports. The concurrent-editor
            // hedge still applies, so it is kept verbatim.
            return " The event's cover was ADDED during this call and is now " + nowUrl
                    + ". That may mean the request applied and only its response was lost, or that"
                    + " something else set it in the meantime — this cannot tell them apart, so"
                    + " check the event rather than re-uploading blind.";
        }
        // The cover moved. The tempting reading is "the write landed and its response was lost",
        // and that is often what happened — but it is not the only thing that produces this. A
        // genuine rejection followed by someone else editing the cover between the two reads looks
        // identical from here, and nothing available correlates the current image with the bytes
        // this call sent. Report the observation and let the caller look, rather than asserting
        // authorship of a change that may not be this one's.
        return " The event's cover CHANGED during this call and is now " + nowUrl
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
     * @param c          the tally, whose record documents what each count means
     * @param rawKnown   whether the live read succeeded at all
     */
    static String coverCaveat(CoverCounts c, boolean rawKnown) {
        if (!rawKnown) {
            return "\n(Recurrence and cover images could not be read, so no event below is marked as"
                    + " recurring or as having a cover even if it is.)";
        }
        StringBuilder sb = new StringBuilder();
        if (c.coverless() > 0) {
            // Covers are rare, so "N of N" is the ordinary case and its arithmetic says nothing.
            // Kept rather than suppressed: this line is why a stale or absent cover stops being
            // invisible, which is the whole reason the listing reads them. The absolute phrasing
            // needs a read that saw every listed event, or it claims over ones it never saw.
            if (c.coverless() == c.described() && c.unreadable() == 0 && c.absent() == 0
                    && c.terminal() == 0 && c.unidentifiable() == 0) {
                sb.append("no event here has a cover image");
            } else {
                // The noun counts described, the verb agrees with coverless: "1 of 3 events has
                // no cover image". Taking both from one number gets one of them wrong.
                sb.append(c.coverless()).append(" of ").append(c.described())
                        .append(c.described() == 1 ? " event" : " events")
                        .append(c.coverless() == 1 ? " has" : " have").append(" no cover image");
            }
        }
        if (c.unreadable() > 0) {
            join(sb).append(c.unreadable()).append(c.unreadable() == 1 ? " event was" : " events were")
                    .append(" returned but could not be read, so ")
                    .append(c.unreadable() == 1 ? "its cover is" : "their covers are").append(" unknown");
        }
        if (c.absent() > 0) {
            join(sb).append(c.absent()).append(c.absent() == 1 ? " event was" : " events were")
                    // Hedged when an entry came back with no usable id, because that entry could
                    // be one of these: nothing matches it to a listed event, so "not in the live
                    // read" stops being knowable.
                    .append(c.unidentifiable() > 0
                            ? " not matched to anything in the live read, and the "
                            + (c.unidentifiable() == 1 ? "entry" : "entries") + " with no id may be"
                            + (c.absent() == 1 ? " that one" : " among them") + ", so "
                            : " not in the live read, so ")
                    .append(c.absent() == 1 ? "its cover is" : "their covers are").append(" unknown");
        }
        if (c.terminal() > 0) {
            join(sb).append(c.terminal()).append(c.terminal() == 1 ? " event has" : " events have")
                    // "ended or been cancelled", not "finished": a cancelled event may never have
                    // started, so the row would read Status: CANCELED beside a header calling it
                    // finished.
                    // Hedged for the same reason the absent clause is: an entry that came back
                    // with no usable id might be this very event, so "no longer returns it" stops
                    // being knowable once one exists.
                    .append(c.unidentifiable() > 0
                            ? " ended or been cancelled, and nothing in the live read matched "
                            : " ended or been cancelled, so Discord no longer returns ")
                    .append(c.terminal() == 1 ? "it" : "them")
                    .append(" and no cover is shown below for ")
                    .append(c.terminal() == 1 ? "it" : "them");
        }
        if (c.unlisted() > 0) {
            // No cause named. A lagging cache is the likeliest one, but a missing intent, a
            // permission-scoped view and a gateway gap look identical from here, and every other
            // clause in this function is careful not to attribute — describeOutcome will not even
            // claim authorship of a cover change for the same reason.
            join(sb).append("Discord returned ").append(c.unlisted()).append(" event")
                    .append(c.unlisted() == 1 ? "" : "s").append(" not in this list, so the list is")
                    .append(" incomplete");
        }
        if (c.unidentifiable() > 0) {
            // Its own clause because it belongs to no event. Folding it into unreadable would
            // name an event that cannot be named, and letting it fall through to absent would
            // blame the cache for a malformed response.
            join(sb).append(c.unidentifiable())
                    .append(c.unidentifiable() == 1 ? " entry" : " entries")
                    .append(" could not be read at all, so ")
                    .append(c.unidentifiable() == 1 ? "it is" : "they are")
                    // Not "either of those": this clause emits whenever there are id-less entries,
                    // including when neither neighbouring clause is present, and then "those" has
                    // no antecedent. "Against any event" holds in both shapes, and still does not
                    // contradict the absent clause's hedge that they may be among its events.
                    .append(" not counted against any event");
        }
        if (c.recurrenceUnreadable() > 0) {
            join(sb).append(c.recurrenceUnreadable())
                    .append(c.recurrenceUnreadable() == 1 ? " event's recurrence" : " events' recurrences")
                    .append(" could not be read, so ")
                    .append(c.recurrenceUnreadable() == 1 ? "it shows" : "they show")
                    .append(" no schedule below even if ")
                    .append(c.recurrenceUnreadable() == 1 ? "it recurs" : "they recur");
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

    /** Over, so the live listing no longer carries it and its cover cannot be read from there. */
    private static boolean isTerminal(ScheduledEvent event) {
        return event.getStatus() == ScheduledEvent.Status.COMPLETED
                || event.getStatus() == ScheduledEvent.Status.CANCELED;
    }

    /** The cover image URL from a raw event object, or null if it has no cover. */
    // Package-private for the same reason as resolveEndTime and coverType: testable without a
    // live event.
    static String coverUrlOf(DataObject raw, String eventId) {
        // Typed check first. getString coerces via toString rather than throwing, so an object or
        // array here would become a hash-shaped nonsense string and be reported as this event's
        // cover — a positive claim from a field that was not readable. Callers already separate
        // "no cover" from "could not read"; this is what puts it on the right side.
        if (raw.hasKey("image") && !raw.isNull("image") && !raw.isType("image", DataType.STRING)) {
            throw new ParsingException("image is not a string for event " + eventId);
        }
        String hash = raw.getString("image", null);
        if (hash == null) {
            return null;
        }
        // Absent means no cover; blank means neither. It is not a hash, so no URL can be built from
        // it — .../{eventId}/.png resolves to nothing — and calling it "no cover image" would be a
        // claim about the event drawn from a field that says nothing.
        if (hash.isBlank()) {
            throw new ParsingException("image is blank for event " + eventId);
        }
        return coverUrl(eventId, hash);
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
                    "A GIF's animation is never shown on a scheduled event cover, so this refuses "
                            + "it rather than silently publishing a still frame. Supply a PNG or "
                            + "JPEG.");
        }
        throw new IllegalArgumentException(paramName
                + " is not a PNG or JPEG. Discord accepts only those for event covers.");
    }

    /**
     * The upload root, or a refusal that points at the parameter needing no filesystem.
     *
     * <p>Also the one place the chained configuration is refused. The README's argument for
     * reusing {@code DISCORD_MCP_FILE_ROOT} rests on the magic-byte check, and that argument is
     * void when the upload root is also the directory {@code download_attachment} writes into: a
     * caller can then fetch a file it chose, PNG header and all, and pin it to a permanent
     * unauthenticated URL. The type-level split between {@code Root} and {@code Path} stops the
     * code confusing the two roots; only this stops the configuration doing it.
     *
     * <p>Refused here rather than at startup, and only on the {@code filePath} branch, so a
     * deployment with the chained config keeps {@code send_file} and {@code imageUrl} working
     * exactly as before — this closes the new capability, not the existing one.
     */
    private LocalFileGuard.Root coverRoot() {
        if (coverFileRoot == null || coverFileRoot.isBlank()) {
            throw new IllegalArgumentException(
                    "Local paths are disabled. Set DISCORD_MCP_FILE_ROOT to the directory this "
                            + "server may read uploads from, or supply imageUrl instead — that "
                            + "needs no filesystem access.");
        }
        LocalFileGuard.Root root =
                LocalFileGuard.resolveRoot(coverFileRoot, "DISCORD_MCP_FILE_ROOT");
        if (downloadRoot == null || downloadRoot.isBlank()) {
            // download_attachment writes nowhere, so there is nothing to collide with. Gated here
            // rather than left to the catch below: an unset variable is this method's ordinary
            // case, and the empty string resolves to the process's working directory, which would
            // refuse every upload root beneath it — including the layout the README recommends.
            return root;
        }
        Path downloads;
        try {
            downloads = LocalFileGuard.resolveRoot(downloadRoot, "DISCORD_MCP_DOWNLOAD_ROOT").path();
        } catch (RuntimeException downloadsUnusable) {
            // Set to something that does not resolve: a missing directory, an unparseable path.
            // Nothing is being written there either, so there is still nothing to collide with,
            // and a broken download root is download_attachment's to report, not this tool's.
            return root;
        }
        // Containment either way, not equality: downloads written inside the upload root are
        // readable from it just as surely, and an upload root inside the downloads directory is
        // the same arrangement seen from the other end. Both sides are already toRealPath()d.
        if (root.path().startsWith(downloads) || downloads.startsWith(root.path())) {
            throw new IllegalArgumentException(
                    "DISCORD_MCP_FILE_ROOT and DISCORD_MCP_DOWNLOAD_ROOT overlap, so this server "
                            + "would read covers out of a directory it also writes downloads "
                            + "into — a file a caller chose, with a PNG header it also chose, "
                            + "pinned to a permanent public URL. Point them at separate "
                            + "directories, or supply imageUrl, which needs no filesystem access.");
        }
        return root;
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

    @Tool(name = "list_guild_scheduled_events", description = "List all active and scheduled events on the server, with each one's recurrence and cover image URL. A header line says how many have no cover, and flags anything the live read could not account for.")
    public String listScheduledEvents(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Whether to include interested user count (default true)", required = false) String withUserCount) {

        Guild guild = getGuild(guildId);
        List<ScheduledEvent> events = guild.getScheduledEvents();

        boolean includeUserCount = withUserCount == null || withUserCount.isEmpty() || Boolean.parseBoolean(withUserCount);

        // One raw list call so recurrence is visible here. Without it a weekly class and a one-off
        // look identical, which is how a recurring event gets edited as though it were not one.
        //
        // The same response also carries the cover image hash, which is read here rather than from
        // ScheduledEvent.getImageUrl() for two reasons: it costs nothing extra, and it is live. A
        // cover changed out of band is exactly the case this listing needs to be right about, and
        // that is the case where JDA's cached entity is stale.
        // The rendered text, not the DataObject. RecurrenceRule.of checks only the top-level
        // shape, so a malformed by_weekday survives it and RecurrenceRule.describe throws. Rendered
        // inside the per-entry guard, that costs one event its schedule line and reaches the
        // recurrenceUnreadable caveat; rendered at display time it would take the listing down.
        // One REST call per listing, including on a guild with nothing cached — where this used
        // to return immediately. Deliberate, and worth naming against REVIEW.md's "no unmetered
        // call patterns": it is one bounded call through JDA's limiter, and it buys the ability to
        // say "none" only when something established it.
        //
        // The per-entry reading lives in LiveEventDetails so it can be tested without driving a
        // RestActionImpl. That is the half where the mistakes were, and every test of the counts
        // and the caveat builds its input by hand — so a wiring error here would leave them green.
        // The whole array goes across, elements included: converting them here first put one
        // getObject per entry outside every per-entry guard, so a single non-object element threw
        // past them and discarded the entries that had already been read.
        LiveEventDetails details;
        boolean rawKnown = false;
        try {
            details = LiveEventDetails.read(fetchRawList(guild.getId()));
            rawKnown = true;
        } catch (RuntimeException e) {
            // Recurrence and cover detail are enhancements to this listing, not its purpose, so
            // losing them must not turn a working list call into a failure. They must not silently
            // read as "nothing recurs" and "no covers" either — that is indistinguishable from the
            // real thing, and the whole point of reporting a missing cover is that its absence is
            // information.
            details = LiveEventDetails.unread();
        }
        Map<String, String> rules = details.rules();
        Map<String, String> covers = details.covers();
        Set<String> described = details.described();
        Set<String> returned = details.returned();
        Set<String> recurrenceFailed = details.recurrenceFailed();
        int unidentifiable = details.unidentifiable();

        // The tally is a pure function of the id sets, extracted for the same reason coverCaveat
        // was: it is where the subtle mistakes live — the absent/terminal split, and unlisted as
        // "returned minus those actually listed" — and a transposition here produces confidently
        // wrong text with nothing failing, because every caveat test calls the formatter directly.
        // none() rather than tallying sets that were just cleared: on a failed read there is
        // nothing to count, and coverCaveat says so from rawKnown alone.
        CoverCounts counts = !rawKnown ? CoverCounts.none() : CoverCounts.tally(
                events.stream().map(ScheduledEvent::getId).toList(),
                events.stream().filter(ScheduledEventService::isTerminal)
                        .map(ScheduledEvent::getId).collect(Collectors.toSet()),
                returned, described, covers.keySet(), recurrenceFailed, unidentifiable);
        String caveat = coverCaveat(counts, rawKnown);
        // An empty cache is not an early return. The main path already reports that case: with
        // nothing listed, every event Discord returned lands in `unlisted`, whose clause says the
        // list is incomplete. A branch of its own would mean a second renderer with its own field
        // set, its own failure policy and no accounting for the entries it skipped. One renderer
        // cannot drift from itself.
        //
        // "None" is still a claim, so it is only made when the live read agreed there are none.
        // unidentifiable too: entries that came back with no usable id are not in `returned`, so
        // without this an all-unreadable response reads as "there are none" and drops the very
        // warning that says otherwise.
        if (events.isEmpty() && rawKnown && returned.isEmpty() && unidentifiable == 0) {
            return "No scheduled events found on this server.";
        }
        String header = "Retrieved " + events.size() + " scheduled events:" + caveat;
        String rows = events.stream()
                .map(e -> renderEvent(e, rules, covers, includeUserCount))
                .collect(Collectors.joining("\n"));
        // The separator only when there is something to separate. An empty cache whose live read
        // also failed reaches here — it cannot claim "none" — and appending the newline anyway
        // hangs the caveat above a blank line, which reads as a truncated answer.
        return rows.isEmpty() ? header : header + "\n" + rows;
    }

    /**
     * One event's block in the listing.
     *
     * <p>Package-private because this is the last hop of the live read: the counts and the caveat
     * are pinned clause by clause and the parse has its own tests, but both sets build their input
     * by hand, so nothing but this reaches {@code covers.get(id)}. Keying a line off the wrong id
     * prints one event's cover under another's name with every other test still green, and a
     * {@code ScheduledEvent} — unlike {@code RestActionImpl} — is something a test can mock.
     */
    static String renderEvent(ScheduledEvent e, Map<String, String> rules,
                              Map<String, String> covers, boolean includeUserCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("- **").append(e.getName()).append("** (ID: ").append(e.getId()).append(")\n");
        sb.append("  • Type: ").append(e.getType()).append(" | Status: ").append(e.getStatus()).append("\n");
        sb.append("  • Start: ").append(e.getStartTime());
        if (e.getEndTime() != null) sb.append(" | End: ").append(e.getEndTime());
        String rule = rules.get(e.getId());
        if (rule != null) sb.append("\n  • Recurs: ").append(rule);
        // Only the URL, and only when there is one. A per-event "none" would be a line of nothing
        // per coverless event on a listing with no result cap; the header count carries that once
        // instead. The recurrence line above omits itself for the same reason.
        String cover = covers.get(e.getId());
        if (cover != null) sb.append("\n  • Cover image: ").append(cover);
        if (includeUserCount) sb.append("\n  • Interested: ").append(e.getInterestedUserCount()).append(" users");
        return sb.toString();
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
