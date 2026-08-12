package dev.saseq.services;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a live scheduled-event list yielded, per event and in total.
 *
 * <p>Separated from the REST call so it can be tested. {@code CoverCounts} and {@code coverCaveat}
 * were extracted for the same reason and are pinned clause by clause — but the code filling these
 * collections was not, and it is the half where the mistakes actually happened: keying a map off
 * the wrong id, adding to {@code described} on the wrong side of a guard, or dropping a
 * {@code returned} entry all produce confidently wrong caveat text with every test still green,
 * because those tests build the counts by hand.
 *
 * <p>Nothing here calls Discord, so a test supplies a {@link DataArray} directly.
 *
 * @param rules            id → rendered recurrence, for events that have one and could be read
 * @param covers           id → cover URL, for events that have one
 * @param described        ids whose cover was read, whether or not there was a cover
 * @param returned         every id Discord returned, parseable details or not
 * @param recurrenceFailed ids whose recurrence would not parse or render
 * @param unidentifiable   entries that belong to no event: not an object, or no usable id
 */
record LiveEventDetails(Map<String, String> rules, Map<String, String> covers,
                        Set<String> described, Set<String> returned,
                        Set<String> recurrenceFailed, int unidentifiable) {

    static LiveEventDetails read(DataArray entries) {
        Map<String, String> rules = new HashMap<>();
        Map<String, String> covers = new HashMap<>();
        Set<String> described = new HashSet<>();
        Set<String> returned = new HashSet<>();
        Set<String> recurrenceFailed = new HashSet<>();
        int unidentifiable = 0;

        for (int i = 0; i < entries.length(); i++) {
            // The array is walked here rather than converted to a list by the caller: that put one
            // getObject per element outside every guard below, so a single non-object element took
            // down the whole read — including every entry already parsed.
            DataObject o;
            try {
                o = entries.getObject(i);
            } catch (RuntimeException notAnEvent) {
                // Discord returned something that is not an event object. It matches no listed
                // event in either direction, which is what unidentifiable counts.
                unidentifiable++;
                continue;
            }
            // Guarded, though nothing in this read is expected to fail: DataObject.getString
            // coerces via toString rather than throwing. The try costs nothing and this method's
            // contract is that one entry cannot cost the rest.
            String id;
            try {
                id = o.getString("id", null);
            } catch (RuntimeException malformed) {
                unidentifiable++;
                continue;
            }
            if (!ScheduledEventService.isSnowflake(id)) {
                // Counted, not silently dropped. Discord did return this event; without a usable
                // id there is no way to say which, so a listed copy of it would otherwise fall
                // into "not in the live read" and nothing would mention it at all.
                //
                // A snowflake, not merely a non-blank string, precisely because of the coercion
                // above: an object or array id arrives here as its toString, which is a string
                // that matches no listed event — every listed id comes from a JDA entity. Recorded,
                // it would land in `unlisted` and manufacture "Discord returned an event this list
                // does not have" out of a malformed field, while the real event it belongs to
                // reads as absent. A numeric id survives, which is the coercion doing something
                // useful: 123 and "123" address the same event.
                unidentifiable++;
                continue;
            }
            // Recorded before the details are parsed: Discord returned this event whatever
            // happens to the rest of it.
            returned.add(id);

            // Parsed independently, and tracked independently. Separate try blocks stop one
            // malformed field discarding the other's value; separate flags stop one field's
            // success standing in as proof the other was read. A single shared flag did the
            // second thing: a field that failed still entered `described`, so the summary counted
            // the event as having no cover — a positive claim drawn from a read that failed.
            //
            // The reachable failure is the recurrence. `image` goes through getString, which
            // coerces rather than throwing, so its guard is defensive; the recurrence read throws
            // for a scalar rule and describe throws for a malformed nested field.
            String rule = null;
            boolean recurrenceRead = false;
            try {
                DataObject parsed = RecurrenceRule.of(o);
                // Rendered here, not at display time: RecurrenceRule.of checks only the top-level
                // shape, so a malformed nested field survives it and describe throws — outside
                // this guard that took down the whole listing.
                rule = parsed == null ? null : RecurrenceRule.describe(parsed);
                recurrenceRead = true;
            } catch (RuntimeException malformed) {
                // Recurrence is lost for this event; its cover may still be readable.
            }
            String cover = null;
            boolean coverRead = false;
            try {
                cover = ScheduledEventService.coverUrlOf(o, id);
                coverRead = true;
            } catch (RuntimeException malformed) {
                // Likewise in reverse.
            }

            // Committed before the cover gate, so an unreadable cover cannot take a good
            // recurrence down with it.
            if (rule != null) {
                rules.put(id, rule);
            }
            if (!recurrenceRead) {
                recurrenceFailed.add(id);
            }
            if (!coverRead) continue;
            described.add(id);
            if (cover != null) {
                covers.put(id, cover);
            }
        }
        return new LiveEventDetails(rules, covers, described, returned, recurrenceFailed, unidentifiable);
    }

    /** Nothing read, so nothing may be claimed from it. */
    static LiveEventDetails unread() {
        return new LiveEventDetails(Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), 0);
    }
}
