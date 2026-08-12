package dev.saseq.services;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How a listing's events divide up, from the point of view of what the live read could say.
 *
 * <p>A record rather than eight positional {@code int}s at the call site. The formatter that
 * consumes these was extracted and tested clause by clause because every clause is a claim about
 * what the reader is looking at — but the arithmetic producing them was not, and that is where the
 * subtle mistakes live. Transposing two arguments in an eight-{@code int} call would have produced
 * confidently wrong text with nothing failing, since the formatter's tests call it directly.
 *
 * <p>Every field answers a different question, and merging any two makes the caveat assert
 * something it does not know. Events come from JDA's gateway-filled cache and covers from a live
 * REST read, so the two can disagree in both directions, an entry can be returned without being
 * parseable, and an event can be legitimately missing because it is over.
 *
 * @param described  listed events the live response described in full
 * @param coverless  how many of those have no cover image
 * @param unreadable listed events Discord returned but whose details would not parse
 * @param absent     listed events Discord did not return, and which it should have
 * @param terminal   listed events Discord did not return because they have ended or been
 *                   cancelled — a different fact from {@code absent}, and one that explains a
 *                   missing cover rather than reporting a gap
 * @param unlistedIds ids Discord returned that the listing does not contain, in the order it
 *                   returned them. Ids rather than a count because this is the one bucket with
 *                   no row of its own: every other clause points the reader at something printed
 *                   below it, and this one had nothing to point at.
 * @param unidentifiable entries Discord returned with no usable id, which cannot be matched to a
 *                       listed event in either direction
 * @param recurrenceUnreadable listed events Discord returned whose recurrence would not parse,
 *                             so a missing "Recurs:" line means unknown rather than absent. An
 *                             event Discord did not return at all has an unknown schedule too,
 *                             but its own clause — absent or terminal — names both missing lines
 */
record CoverCounts(int described, int coverless, int unreadable, int absent, int terminal,
                   List<String> unlistedIds, int unidentifiable, int recurrenceUnreadable) {

    /** How many events Discord returned that the listing does not contain. */
    int unlisted() {
        return unlistedIds.size();
    }

    /**
     * @param listed         every listed event's id, in listing order
     * @param terminalIds    those of them that have ended or been cancelled
     * @param returned       ids the live response carried, parseable or not
     * @param described      ids whose cover was read, whether or not there was one
     * @param withCovers     ids that have a cover
     * @param recurrenceFailed ids whose recurrence would not parse
     * @param unidentifiable entries with no usable id, which belong to no event
     */
    static CoverCounts tally(Collection<String> listed, Set<String> terminalIds,
                             Set<String> returned, Set<String> described, Set<String> withCovers,
                             Set<String> recurrenceFailed, int unidentifiable) {
        // Distinct, so a duplicate id in the listing cannot be counted into two buckets at once.
        // Nothing in Collection<String> stops a caller passing one.
        Set<String> unique = new LinkedHashSet<>(listed);
        int describedCount = 0;
        int coverless = 0;
        int unreadable = 0;
        int absent = 0;
        int terminal = 0;
        int recurrenceUnreadable = 0;
        for (String id : unique) {
            if (described.contains(id)) {
                describedCount++;
                if (!withCovers.contains(id)) {
                    coverless++;
                }
            } else if (returned.contains(id)) {
                // Returned, but its details would not parse. Distinct from absent: Discord did
                // send this event, so blaming a read gap would send the reader to look in the
                // wrong place.
                unreadable++;
            } else if (terminalIds.contains(id)) {
                // Not returned because it is over. GET /guilds/{id}/scheduled-events carries
                // scheduled and active events only, so this is expected rather than a gap — but
                // the row still renders with no cover, and that needs saying.
                terminal++;
            } else {
                absent++;
            }
            // Only the parse failures. An event Discord did not return had no recurrence read
            // either — and its row renders exactly like a one-off, which is the confusion this
            // recurrence read exists to remove — but its own clause is where that is said. Both
            // the absent and the terminal clause name the cover and the schedule together, so
            // counting those events here as well would give one event two accounts, the second
            // saying its recurrence "could not be read": a parse failure, about an event the
            // line before had just explained never arrived.
            if (recurrenceFailed.contains(id)) {
                recurrenceUnreadable++;
            }
        }
        // Returned minus those actually in the listing: events Discord has and the cache does
        // not. A set difference rather than a subtraction of two counts, which is both the ids the
        // caveat needs and a shape that cannot render "Discord returned -1 events" however the
        // caller's collections overlap.
        List<String> unlistedIds = returned.stream().filter(id -> !unique.contains(id)).toList();
        return new CoverCounts(describedCount, coverless, unreadable, absent, terminal, unlistedIds,
                unidentifiable, recurrenceUnreadable);
    }

    /** Nothing to say: the live read accounted for every listed event, and all of them have covers. */
    static CoverCounts none() {
        return new CoverCounts(0, 0, 0, 0, 0, List.of(), 0, 0);
    }
}
