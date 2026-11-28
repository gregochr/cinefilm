package gregochr.cinefilm.journey;

import java.time.Instant;
import java.util.List;
import java.util.NavigableMap;

/**
 * The minute rows of one playback (a part of a viewing) and what decision 5 needs to judge them. A row is
 * {@code [minute, median kbit/s, rung on screen, ms moved, ms stalled, s ahead, rung fetched]}; the last two are
 * missing in an old report. {@code seeked} is true unless the report counted no seek; {@code endPositionMs} and
 * {@code knownRuntimeMs} are null when unknown. Pure.
 */
record PartMinutes(Instant startedAt, boolean seeked, Long endPositionMs, Long knownRuntimeMs,
                   NavigableMap<Long, List<Long>> rows) {

    /** Moved plus stalled, or moved, at least this long makes a minute one the film was playing in. */
    static final long PLAYING_MS = 45_000;
    /** A median under this, in kbit/s, is a slow minute. */
    static final long SLOW_KBPS = 500;
    /** How far before the film's known runtime the bank may be and still count as at its end. */
    static final long FILM_END_MARGIN_MS = 60_000;

    private static final long MINUTE_MS = 60_000;
    private static final int MINUTE = 0;
    private static final int MEDIAN = 1;
    private static final int MOVED = 3;
    private static final int STALLED = 4;
    private static final int AHEAD = 5;
    private static final int ROW_WITH_AHEAD = 7;

    /** The row whose minute contains {@code at} (start + n minutes up to, not including, n + 1), or null. */
    List<Long> rowAt(Instant at) {
        long elapsed = at.toEpochMilli() - startedAt.toEpochMilli();
        return elapsed < 0 ? null : rows.get(elapsed / MINUTE_MS);
    }

    /** The minute's own verdict: {@code GOOD}, {@code BAD}, or {@code UNKNOWN} for no verdict. */
    PointState verdict(List<Long> row) {
        long minute = row.get(MINUTE);
        long moved = row.get(MOVED);
        long stalled = row.get(STALLED);
        if (moved + stalled >= PLAYING_MS && stalled > 0 && minute > 0) {
            return PointState.BAD;
        }
        if (moved < PLAYING_MS) {
            return PointState.UNKNOWN;
        }
        long median = row.get(MEDIAN);
        if (median > 0) {
            return median < SLOW_KBPS ? PointState.BAD : PointState.GOOD;
        }
        return noFragment(row, minute);
    }

    /** A playing minute that fetched nothing: bad unless the bank had reached the film's end or that cannot be told. */
    private PointState noFragment(List<Long> row, long minute) {
        if (seeked || endPositionMs == null || knownRuntimeMs == null || row.size() < ROW_WITH_AHEAD) {
            return PointState.UNKNOWN;
        }
        long laterMoved = rows.tailMap(minute, false).values().stream().mapToLong(r -> r.get(MOVED)).sum();
        long bankEnd = endPositionMs - laterMoved + row.get(AHEAD) * 1000;
        return bankEnd >= knownRuntimeMs - FILM_END_MARGIN_MS ? PointState.UNKNOWN : PointState.BAD;
    }
}
