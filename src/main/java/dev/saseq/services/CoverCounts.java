package dev.saseq.services;

import java.util.Collection;
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
 * @param unlisted   events Discord returned that the listing does not contain
 * @param unidentifiable entries Discord returned with no usable id, which cannot be matched to a
 *                       listed event in either direction
 * @param recurrenceUnreadable listed events whose recurrence would not parse, so a missing
 *                             "Recurs:" line means unknown rather than absent
 */
record CoverCounts(int described, int coverless, int unreadable, int absent, int terminal,
                   int unlisted, int unidentifiable, int recurrenceUnreadable) {

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
        // Distinct, because `unlisted` subtracts this from returned.size(). A duplicate id in the
        // listing would make listedAndReturned outrun a Set and render "Discord returned -1
        // events not in this list" — nonsense from a function whose whole purpose is not making
        // claims it cannot support. Nothing in Collection<String> stops a caller passing one.
        listed = new java.util.LinkedHashSet<>(listed);
        int describedCount = 0;
        int coverless = 0;
        int unreadable = 0;
        int absent = 0;
        int terminal = 0;
        int listedAndReturned = 0;
        int recurrenceUnreadable = 0;
        for (String id : listed) {
            if (returned.contains(id)) {
                listedAndReturned++;
            }
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
            if (recurrenceFailed.contains(id)) {
                recurrenceUnreadable++;
            }
        }
        // Returned minus those that are actually in the listing: events Discord has and the cache
        // does not. These have no row at all, so there is nowhere to hang a per-event note.
        int unlisted = returned.size() - listedAndReturned;
        return new CoverCounts(describedCount, coverless, unreadable, absent, terminal, unlisted,
                unidentifiable, recurrenceUnreadable);
    }

    /** Nothing to say: the live read accounted for every listed event, and all of them have covers. */
    static CoverCounts none() {
        return new CoverCounts(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
