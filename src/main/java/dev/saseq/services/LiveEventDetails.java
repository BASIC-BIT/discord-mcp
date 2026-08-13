package dev.saseq.services;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
 * @param returned         every id Discord returned, parseable details or not, in the order it
 *                         returned them — the caveat names these and prints only the first ten
 * @param recurrenceFailed ids whose recurrence would not parse or render
 * @param unidentifiable   entries that belong to no event: not an object, or no usable id
 */
record LiveEventDetails(Map<String, String> rules, Map<String, String> covers,
                        Set<String> described, List<String> returned,
                        Set<String> recurrenceFailed, int unidentifiable) {

    LiveEventDetails {
        // Copied, so this is a value rather than five live handles into read()'s working state.
        //
        // `returned` is a List because its order is load-bearing: the caveat names these ids
        // and prints the first ten, so an arbitrary order changes which ten a caller is handed
        // to act on. A Set says nothing about order — Set.copyOf in particular iterates in an
        // order randomised per JVM run from an internal salt, precisely so nothing depends on
        // it — and this file has spent enough rounds moving guarantees out of comments and into
        // types to leave this one in a comment.
        //
        // read() still collects into a LinkedHashSet, so the ids are deduplicated on the way in
        // and the List cannot carry the same event twice.
        //
        // The other two are membership tests — nothing iterates them — so copyOf is right there.
        rules = Map.copyOf(rules);
        covers = Map.copyOf(covers);
        described = Set.copyOf(described);
        returned = List.copyOf(returned);
        recurrenceFailed = Set.copyOf(recurrenceFailed);
    }

    static LiveEventDetails read(DataArray entries) {
        Map<String, String> rules = new HashMap<>();
        Map<String, String> covers = new HashMap<>();
        Set<String> described = new HashSet<>();
        // Insertion-ordered: the caveat names unlisted ids, and the order Discord
        // returned them in is the only order this has any claim to.
        Set<String> returned = new LinkedHashSet<>();
        Set<String> recurrenceFailed = new HashSet<>();
        int unidentifiable = 0;

        for (int i = 0; i < entries.length(); i++) {
            // The array is walked here rather than converted to a list by the caller. Converting
            // it there puts one getObject per element outside every guard below, where a single
            // non-object element discards the whole read, entries already parsed included.
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
            // success standing in as proof the other was read. Under one shared flag, a field
            // that failed would still enter `described`, and the summary would count the event as
            // having no cover — a positive claim drawn from a read that failed.
            //
            // The reachable failure is the recurrence. `image` goes through getString, which
            // coerces rather than throwing, so its guard is defensive; the recurrence read throws
            // for a scalar rule and describe throws for a malformed nested field.
            String rule = null;
            boolean recurrenceRead = false;
            try {
                DataObject parsed = RecurrenceRule.of(o);
                // Rendered here, not at display time: RecurrenceRule.of checks only the top-level
                // shape, so a malformed nested field survives it and describe throws. Outside this
                // guard, that throw takes the whole listing down.
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
        return new LiveEventDetails(rules, covers, described, List.copyOf(returned),
                recurrenceFailed, unidentifiable);
    }

    /** Nothing read, so nothing may be claimed from it. */
    static LiveEventDetails unread() {
        return new LiveEventDetails(Map.of(), Map.of(), Set.of(), List.of(), Set.of(), 0);
    }
}
