package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * How two subtitles' cues are compared once some of them may have been retimed (docs/engineering/
 * subtitle-frame-rate-plan.md, "How two files are compared"). Every place that compares two subtitles goes through
 * {@link #judge}. A side's rate is its stated {@code timed_fps}; an owner's file, the embedded track and a download
 * that fits as it is ({@link RetimeChoice#OFF_FITS}) take the film's rate. A rate's family is "about 24" (within 0.01
 * of 23.976 or 24.0), "25" (within 0.01 of 25) or other. A side's original is its original cues when retimed, else its
 * stored cues. In this order:
 * <ol>
 *   <li>neither side retimed: stored against stored, whatever the rates (unknown included); the film rate is never
 *       asked for;</li>
 *   <li>a rate not known: {@link Reason#UNKNOWN_RATE}, no verdict;</li>
 *   <li>same family: original against original (they share a timeline). A stated rate is "about 24" within 0.01 of
 *       23.976 or 24.0 and "25" within 0.01 of 25; a film's rate (an owner's file, the embedded track, a download that
 *       fits) is "about 24" in 23.9 to 24.1 and "25" in 24.85 to 25.15; any other known rate is a family of its
 *       own;</li>
 *   <li>different families: stored against stored, on the film's timeline. When either side is a reference and that
 *       fails, the originals are compared too, and when they agree there is {@link Reason#RATE_CONFLICT}, no verdict:
 *       one stated rate is wrong. The film-timeline result stands for a download against an owner's file, the
 *       embedded track or a download that fits as it is.</li>
 * </ol>
 */
public final class RateComparison {

    private static final double ABOUT_24_LOW = 23.976;
    private static final double ABOUT_24_HIGH = 24.0;
    private static final double PAL = 25.0;
    private static final double TOLERANCE = 0.01;
    private static final double SLACK = 1e-9;
    private static final double FILM_24_MIN = 23.9;
    private static final double FILM_24_MAX = 24.1;
    private static final double PAL_FILM_MIN = 24.85;
    private static final double PAL_FILM_MAX = 25.15;

    private RateComparison() {
    }

    /** What a side is. */
    public enum Kind {
        /** A subtitle downloaded from OpenSubtitles for an edition. */
        DOWNLOAD,
        /** A stored OpenSubtitles reference. */
        REFERENCE,
        /** The owner's file beside the film. */
        FILE,
        /** The film's embedded English track. */
        EMBEDDED
    }

    /** Why there is no verdict. */
    public enum Reason {
        /** A retimed side is compared with one whose rate is not known. */
        UNKNOWN_RATE,
        /** A retimed side and a raw side stated at different rates, the raw one not on the film's timeline. */
        RATE_CONFLICT
    }

    /**
     * One side of a comparison.
     *
     * @param kind what it is
     * @param timedFps the rate it states, or null
     * @param retime the retime decision of a download, or null
     * @param retimedBy the factor its stored cues were multiplied by; null when never retimed
     * @param originalCuesText the cues before they were retimed; null until then
     */
    public record Side(Kind kind, Double timedFps, RetimeChoice retime, Double retimedBy, String originalCuesText) {

        /** The owner's file: on the film's timeline, never retimed. */
        public static Side file() {
            return new Side(Kind.FILE, null, null, null, null);
        }

        /** The film's embedded track: on the film's timeline by nature. */
        public static Side embedded() {
            return new Side(Kind.EMBEDDED, null, null, null, null);
        }

        /** The side a stored subtitle is: a download, or the owner's file. */
        public static Side of(SubtitleSidecar sidecar) {
            return new Side(sidecar.isDownload() ? Kind.DOWNLOAD : Kind.FILE, sidecar.getTimedFps(),
                sidecar.getRetime(), sidecar.getRetimedBy(), sidecar.getOriginalCuesText());
        }

        /** The side a stored reference is. */
        public static Side of(OpenSubtitlesReference reference) {
            return new Side(Kind.REFERENCE, reference.getTimedFps(), null, reference.getRetimedBy(),
                reference.getOriginalCuesText());
        }

        /** True when its stored cues were rewritten onto the film's timeline. */
        public boolean retimed() {
            return retimedBy != null;
        }

        /** Its rate: the film's for a file, the embedded track or a download that fits; else the stated one. */
        Double rate(Double filmFps) {
            return onFilm() ? filmFps : timedFps;
        }

        /** True for an owner's file, the embedded track and a download that fits as it is: on the film's timeline. */
        boolean onFilm() {
            return kind == Kind.FILE || kind == Kind.EMBEDDED || retime == RetimeChoice.OFF_FITS;
        }
    }

    private enum Family { ABOUT_24, PAL, OTHER }

    /**
     * The outcome of a comparison.
     *
     * @param result the comparison's result, or null when there is no verdict
     * @param first the cues of the first side that were compared (empty when there is no verdict)
     * @param second the cues of the second side that were compared (empty when there is no verdict)
     * @param reason why there is no verdict, or null when there is one
     * @param <R> the result type of the site's comparison
     */
    public record Judged<R>(R result, List<SubtitleCue> first, List<SubtitleCue> second, Reason reason) {

        /** True when the two sides were compared and the result stands. */
        public boolean hasVerdict() {
            return reason == null;
        }
    }

    /**
     * Compares two sides by the rule above.
     *
     * @param first one side
     * @param second the other side
     * @param filmFps the film's rate; asked for only when a side is retimed. May return null (unknown)
     * @param firstStored the first side's stored cues
     * @param secondStored the second side's stored cues
     * @param compare the site's comparison of two cue lists
     * @param fails whether a result would fail (the walk would move on, or CHECK)
     * @param agrees whether a result is an agreement
     * @param <R> the result type
     * @return the result with the cues compared, or why there is no verdict
     */
    public static <R> Judged<R> judge(Side first, Side second, Supplier<Double> filmFps,
                                      List<SubtitleCue> firstStored, List<SubtitleCue> secondStored,
                                      BiFunction<List<SubtitleCue>, List<SubtitleCue>, R> compare,
                                      Predicate<R> fails, Predicate<R> agrees) {
        if (!first.retimed() && !second.retimed()) {
            return new Judged<>(compare.apply(firstStored, secondStored), firstStored, secondStored, null);
        }
        Double film = filmFps.get();
        Double firstRate = first.rate(film);
        Double secondRate = second.rate(film);
        if (firstRate == null || secondRate == null || first.retimed() && first.originalCuesText() == null
            || second.retimed() && second.originalCuesText() == null) {
            return none(Reason.UNKNOWN_RATE);
        }
        List<SubtitleCue> firstOriginal = first.retimed() ? SrtParser.cues(first.originalCuesText()) : firstStored;
        List<SubtitleCue> secondOriginal = second.retimed() ? SrtParser.cues(second.originalCuesText())
            : secondStored;
        if (sameFamily(family(first, firstRate), family(second, secondRate), firstRate, secondRate)) {
            return new Judged<>(compare.apply(firstOriginal, secondOriginal), firstOriginal, secondOriginal, null);
        }
        R onFilm = compare.apply(firstStored, secondStored);
        boolean eitherReference = first.kind() == Kind.REFERENCE || second.kind() == Kind.REFERENCE;
        if (eitherReference && fails.test(onFilm)
            && agrees.test(compare.apply(firstOriginal, secondOriginal))) {
            return none(Reason.RATE_CONFLICT);
        }
        return new Judged<>(onFilm, firstStored, secondStored, null);
    }

    private static <R> Judged<R> none(Reason reason) {
        return new Judged<>(null, List.of(), List.of(), reason);
    }

    private static boolean sameFamily(Family a, Family b, double rateA, double rateB) {
        return a == b && (a != Family.OTHER || FrameRateRetime.sameRate(rateA, rateB));
    }

    /** A stated rate by its bands, a film rate (an owner's file and the like) by the film bands of the retime. */
    private static Family family(Side side, double rate) {
        if (side.onFilm()) {
            if (rate >= FILM_24_MIN - SLACK && rate <= FILM_24_MAX + SLACK) {
                return Family.ABOUT_24;
            }
            return rate >= PAL_FILM_MIN - SLACK && rate <= PAL_FILM_MAX + SLACK ? Family.PAL : Family.OTHER;
        }
        if (Math.abs(rate - ABOUT_24_LOW) <= TOLERANCE + SLACK || Math.abs(rate - ABOUT_24_HIGH) <= TOLERANCE + SLACK) {
            return Family.ABOUT_24;
        }
        return Math.abs(rate - PAL) <= TOLERANCE + SLACK ? Family.PAL : Family.OTHER;
    }
}
