package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.subtitle.RateComparison.Judged;
import gregochr.cinefilm.subtitle.RateComparison.Kind;
import gregochr.cinefilm.subtitle.RateComparison.Reason;
import gregochr.cinefilm.subtitle.RateComparison.Side;
import gregochr.cinefilm.subtitle.SubtitleSidecar.Origin;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The rate rule: which cues of two subtitles are compared, or why there is no verdict. Pure. */
class RateComparisonTest {

    private static final String SAME = "same";
    private static final String DIFFERENT = "different";
    private static final Supplier<Double> FILM_25 = () -> 25.0;
    private static final Supplier<Double> FILM_23976 = () -> 23.976;
    private static final Supplier<Double> FILM_UNKNOWN = () -> null;
    private static final Supplier<Double> NOT_ASKED = () -> {
        throw new AssertionError("the film's rate is not needed when nothing is retimed");
    };

    /** A one-cue file whose text names the timeline it is on. */
    private static List<SubtitleCue> cues(String timeline) {
        return List.of(new SubtitleCue(0, 1_000, timeline));
    }

    private static String text(String timeline) {
        return SrtParser.format(cues(timeline));
    }

    private static Side retimedDownload(double fps, String original) {
        return new Side(Kind.DOWNLOAD, fps, RetimeChoice.AUTO, fps / 25.0, text(original));
    }

    private static Side rawDownload(double fps, RetimeChoice retime) {
        return new Side(Kind.DOWNLOAD, fps, retime, null, null);
    }

    private static Side retimedReference(double fps, String original) {
        return new Side(Kind.REFERENCE, fps, null, fps / 25.0, text(original));
    }

    private static Side rawReference(Double fps) {
        return new Side(Kind.REFERENCE, fps, null, null, null);
    }

    private static Judged<String> judge(Side first, String firstStored, Side second, String secondStored,
                                        Supplier<Double> film, List<List<String>> compared) {
        return RateComparison.judge(first, second, film, cues(firstStored), RateComparison.originalOf(first),
            cues(secondStored), RateComparison.originalOf(second), (a, b) -> {
                compared.add(List.of(a.get(0).text(), b.get(0).text()));
                return a.equals(b) ? SAME : DIFFERENT;
            }, DIFFERENT::equals, SAME::equals);
    }

    @Test
    void withNothingRetimedTheStoredCuesAreComparedWhateverTheRatesAndTheFilmRateIsNotAsked() {
        List<List<String>> compared = new ArrayList<>();

        Judged<String> owner = judge(Side.file(), "a", rawReference(null), "a", NOT_ASKED, compared);
        Judged<String> offsetDownload = judge(rawDownload(23.976, RetimeChoice.OFF), "a", rawReference(25.0), "b",
            NOT_ASKED, compared);

        assertThat(owner.hasVerdict()).isTrue();
        assertThat(owner.result()).isEqualTo(SAME);
        assertThat(offsetDownload.result()).isEqualTo(DIFFERENT);
        assertThat(compared).containsExactly(List.of("a", "a"), List.of("a", "b"));
    }

    @Test
    void aRawDownloadOfTheSameFamilyAgainstARetimedReferenceIsComparedWithTheReferencesOriginal() {
        List<List<String>> compared = new ArrayList<>();

        Judged<String> judged = judge(rawDownload(23.976, RetimeChoice.OFF), "original", retimedReference(23.976,
            "original"), "film", FILM_25, compared);

        assertThat(judged.result()).isEqualTo(SAME);
        assertThat(compared).containsExactly(List.of("original", "original"));
        assertThat(judged.first()).isEqualTo(cues("original"));
        assertThat(judged.second()).isEqualTo(cues("original"));
    }

    @Test
    void aReferenceOfUnknownRateAgainstARetimedDownloadHasNoVerdict() {
        List<List<String>> compared = new ArrayList<>();

        Judged<String> judged = judge(retimedDownload(23.976, "original"), "film", rawReference(null), "x", FILM_25,
            compared);

        assertThat(judged.hasVerdict()).isFalse();
        assertThat(judged.reason()).isEqualTo(Reason.UNKNOWN_RATE);
        assertThat(judged.result()).isNull();
        assertThat(compared).isEmpty();
    }

    @Test
    void aRetimedSideWithNoOriginalHasNoVerdict() {
        Side broken = new Side(Kind.DOWNLOAD, 23.976, RetimeChoice.AUTO, 0.959, null);

        Judged<String> judged = judge(broken, "film", rawReference(23.976), "x", FILM_25, new ArrayList<>());

        assertThat(judged.reason()).isEqualTo(Reason.UNKNOWN_RATE);
    }

    @Test
    void aRetimedDownloadAgainstTheEmbeddedTrackIsComparedOnTheFilmTimelineAndItsResultStands() {
        List<List<String>> compared = new ArrayList<>();

        Judged<String> agrees = judge(retimedDownload(23.976, "original"), "film", Side.embedded(), "film", FILM_25,
            compared);
        Judged<String> disagrees = judge(retimedDownload(23.976, "original"), "film", Side.embedded(), "other",
            FILM_25, compared);

        assertThat(agrees.result()).isEqualTo(SAME);
        assertThat(disagrees.hasVerdict()).isTrue();
        assertThat(disagrees.result()).isEqualTo(DIFFERENT);
        assertThat(compared).containsExactly(List.of("film", "film"), List.of("film", "other"));
    }

    @Test
    void aRetimedDownloadAgainstARawReferenceStated25ThatIsTimedLike24IsARateConflict() {
        List<List<String>> compared = new ArrayList<>();

        // On the film's timeline they differ; as originally stored they are the same file.
        Judged<String> judged = judge(retimedDownload(23.976, "same file"), "film", rawReference(25.0), "same file",
            FILM_25, compared);

        assertThat(judged.hasVerdict()).isFalse();
        assertThat(judged.reason()).isEqualTo(Reason.RATE_CONFLICT);
        assertThat(judged.result()).isNull();
        assertThat(compared).containsExactly(List.of("film", "same file"), List.of("same file", "same file"));
    }

    @Test
    void theSameRetimedDownloadAgainstTheOwnersFileThatDisagreesOnTheFilmTimelineFailsWithNoConflict() {
        List<List<String>> compared = new ArrayList<>();

        Judged<String> judged = judge(retimedDownload(23.976, "same file"), "film", Side.file(), "same file",
            FILM_25, compared);

        assertThat(judged.hasVerdict()).isTrue();
        assertThat(judged.result()).isEqualTo(DIFFERENT);
        assertThat(compared).containsExactly(List.of("film", "same file"));
    }

    @Test
    void aDownloadThatFitsAsItIsTakesTheFilmsFamilyAndItsDisagreementStands() {
        List<List<String>> compared = new ArrayList<>();

        Judged<String> judged = judge(retimedDownload(23.976, "same file"), "film", rawDownload(24.0,
            RetimeChoice.OFF_FITS), "same file", FILM_25, compared);

        assertThat(judged.result()).isEqualTo(DIFFERENT);
        assertThat(compared).containsExactly(List.of("film", "same file"));
    }

    @Test
    void aRawDownloadWithItsTimingLeftAloneThatDisagreesStandsToo() {
        Judged<String> judged = judge(retimedDownload(23.976, "same file"), "film", rawDownload(25.0,
            RetimeChoice.OFF), "same file", FILM_25, new ArrayList<>());

        assertThat(judged.result()).isEqualTo(DIFFERENT);
    }

    @Test
    void aPairOfDifferentFamiliesThatAgreesOnTheFilmTimelineGivesThatAndTheOriginalsAreNotCompared() {
        List<List<String>> compared = new ArrayList<>();

        Judged<String> judged = judge(retimedDownload(23.976, "original"), "film", rawReference(25.0), "film",
            FILM_25, compared);

        assertThat(judged.result()).isEqualTo(SAME);
        assertThat(compared).containsExactly(List.of("film", "film"));
    }

    @Test
    void aRetimed23976DownloadOnA25FilmAgainstARawReferenceStated25FailsWhenTheOriginalsDisagreeToo() {
        List<List<String>> compared = new ArrayList<>();

        Judged<String> judged = judge(retimedDownload(23.976, "original"), "film", rawReference(25.0), "other",
            FILM_25, compared);

        assertThat(judged.result()).isEqualTo(DIFFERENT);
        assertThat(compared).containsExactly(List.of("film", "other"), List.of("original", "other"));
    }

    @Test
    void twoRetimedSidesOfTheSameFamilyAreComparedOnTheirOriginalsAndOfDifferentFamiliesOnTheFilmTimeline() {
        List<List<String>> compared = new ArrayList<>();

        judge(retimedDownload(23.976, "o1"), "f1", retimedReference(24.0, "o2"), "f2", FILM_25, compared);
        judge(retimedDownload(23.976, "o1"), "f1", new Side(Kind.REFERENCE, 25.0, null, 0.5, text("o2")), "f2",
            FILM_23976, compared);

        assertThat(compared).containsExactly(List.of("o1", "o2"), List.of("f1", "f2"), List.of("o1", "o2"));
    }

    @Test
    void otherRatesShareAFamilyOnlyWhenTheyAreTheSameRate() {
        List<List<String>> compared = new ArrayList<>();

        judge(retimedDownload(30.0, "o1"), "f1", rawReference(30.0), "o1", FILM_25, compared);
        judge(retimedDownload(30.0, "o1"), "f1", rawReference(29.97), "f2", FILM_25, compared);

        assertThat(compared).containsExactly(List.of("o1", "o1"), List.of("f1", "f2"), List.of("o1", "f2"));
    }

    @Test
    void aRetimedReferenceStated25ButTimedLike24AgainstTheOwnersFileOnA23976FilmIsARateConflict() {
        List<List<String>> compared = new ArrayList<>();

        Judged<String> judged = judge(retimedReference(25.0, "same file"), "film", Side.file(), "same file",
            FILM_23976, compared);

        assertThat(judged.reason()).isEqualTo(Reason.RATE_CONFLICT);
        assertThat(compared).containsExactly(List.of("film", "same file"), List.of("same file", "same file"));
    }

    @Test
    void aRetimedReferenceThatAgreesWithTheOwnersFileOnTheFilmTimelineGivesThatResult() {
        List<List<String>> compared = new ArrayList<>();

        Judged<String> judged = judge(retimedReference(25.0, "original"), "film", Side.file(), "film", FILM_23976,
            compared);

        assertThat(judged.result()).isEqualTo(SAME);
        assertThat(compared).containsExactly(List.of("film", "film"));
    }

    @Test
    void aReferencePairOfDifferentFamiliesThatFailsOnTheFilmTimelineAndOnTheOriginalsFailsWithNoConflict() {
        List<List<String>> compared = new ArrayList<>();

        Judged<String> judged = judge(retimedReference(23.976, "original"), "film", rawReference(25.0), "other",
            FILM_25, compared);

        assertThat(judged.hasVerdict()).isTrue();
        assertThat(judged.result()).isEqualTo(DIFFERENT);
        assertThat(compared).containsExactly(List.of("film", "other"), List.of("original", "other"));
    }

    @Test
    void aRawDownloadStated25AgainstARetimedReferenceOf23976IsARateConflictWhenTheOriginalsAgree() {
        List<List<String>> compared = new ArrayList<>();

        Judged<String> judged = judge(rawDownload(25.0, RetimeChoice.OFF), "same file", retimedReference(23.976,
            "same file"), "film", FILM_25, compared);

        assertThat(judged.reason()).isEqualTo(Reason.RATE_CONFLICT);
        assertThat(compared).containsExactly(List.of("same file", "film"), List.of("same file", "same file"));
    }

    @Test
    void aRawDownloadStated25AgainstARetimedReferenceOf23976FailsWhenTheOriginalsDisagree() {
        List<List<String>> compared = new ArrayList<>();

        Judged<String> judged = judge(rawDownload(25.0, RetimeChoice.OFF), "a file", retimedReference(23.976,
            "another"), "film", FILM_25, compared);

        assertThat(judged.hasVerdict()).isTrue();
        assertThat(judged.result()).isEqualTo(DIFFERENT);
        assertThat(compared).containsExactly(List.of("a file", "film"), List.of("a file", "another"));
    }

    @Test
    void withNoReferenceOnEitherSideTheOriginalsAreNeverComparedWhateverTheResult() {
        List<List<String>> compared = new ArrayList<>();

        Judged<String> file = judge(retimedDownload(23.976, "same file"), "film", Side.file(), "same file", FILM_25,
            compared);
        Judged<String> embedded = judge(retimedDownload(23.976, "same file"), "film", Side.embedded(), "same file",
            FILM_25, compared);
        Judged<String> fits = judge(retimedDownload(23.976, "same file"), "film", rawDownload(24.0,
            RetimeChoice.OFF_FITS), "same file", FILM_25, compared);

        assertThat(List.of(file.result(), embedded.result(), fits.result())).containsOnly(DIFFERENT);
        assertThat(compared).hasSize(3).allSatisfy(pair -> assertThat(pair).containsExactly("film", "same file"));
    }

    @Test
    void aSideIsDescribedFromItsRow() {
        SubtitleSidecar download = new SubtitleSidecar();
        download.setOrigin(Origin.OPENSUBTITLES);
        download.setTimedFps(23.976);
        download.setRetime(RetimeChoice.AUTO);
        download.setRetimedBy(0.959);
        download.setOriginalCuesText("orig");
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setTimedFps(24.0);
        reference.setRetimedBy(0.96);
        reference.setOriginalCuesText("refOrig");

        assertThat(Side.of(download)).isEqualTo(new Side(Kind.DOWNLOAD, 23.976, RetimeChoice.AUTO, 0.959, "orig"));
        assertThat(Side.of(new SubtitleSidecar())).isEqualTo(new Side(Kind.FILE, null, null, null, null));
        assertThat(Side.of(reference)).isEqualTo(new Side(Kind.REFERENCE, 24.0, null, 0.96, "refOrig"));
        assertThat(Side.of(reference).retimed()).isTrue();
        assertThat(Side.file().retimed()).isFalse();
        assertThat(Side.embedded().kind()).isEqualTo(Kind.EMBEDDED);
    }

    @Test
    void aRetimedSideOnEitherPlaceWithNoOriginalHasNoVerdict() {
        Side broken = new Side(Kind.REFERENCE, 23.976, null, 0.959, null);

        Judged<String> judged = judge(rawReference(23.976), "x", broken, "film", FILM_25, new ArrayList<>());

        assertThat(judged.reason()).isEqualTo(Reason.UNKNOWN_RATE);
    }

    /**
     * Whether the originals or the film timeline were compared first, for a retimed download of one stated rate against
     * the owner's file or the embedded track (whose rate is the film's, the third column) or a raw reference (whose
     * rate is its stated one, on a 25 fps film). A reference pair that fails on the film timeline is looked at on the
     * originals afterwards, so the first comparison tells the timelines apart.
     */
    @ParameterizedTest(name = "{0} {2} against a retimed download stated {1}: {3}")
    @CsvSource({
        // film bands: about 24 is 23.9 to 24.1, 25 is 24.85 to 25.15, anything else is a family of its own
        "FILE, 23.976, 23.899, FILM", "FILE, 23.976, 23.9, ORIGINALS", "FILE, 23.976, 24.0, ORIGINALS",
        "EMBEDDED, 23.976, 24.1, ORIGINALS", "EMBEDDED, 23.976, 24.101, FILM",
        "FILE, 25.0, 24.849, FILM", "FILE, 25.0, 24.85, ORIGINALS", "EMBEDDED, 25.0, 25.0, ORIGINALS",
        "EMBEDDED, 25.0, 25.15, ORIGINALS", "FILE, 25.0, 25.151, FILM",
        "FILE, 24.0, 24.05, ORIGINALS", "FILE, 23.976, 20.0, FILM", "FILE, 23.976, 24.5, FILM",
        "FILE, 23.976, 25.2, FILM", "EMBEDDED, 23.976, 30.0, FILM", "EMBEDDED, 23.976, 24.98, FILM",
        // stated bands: within 0.01 of 23.976 or 24.0, or of 25, else a family of its own
        "REFERENCE, 23.976, 23.976, ORIGINALS", "REFERENCE, 23.976, 24.0, ORIGINALS",
        "REFERENCE, 23.98, 23.976, ORIGINALS", "REFERENCE, 23.976, 23.966, ORIGINALS",
        "REFERENCE, 23.976, 23.965, FILM", "REFERENCE, 23.976, 23.986, ORIGINALS",
        "REFERENCE, 23.976, 23.987, FILM", "REFERENCE, 23.976, 23.99, ORIGINALS",
        "REFERENCE, 23.976, 23.989, FILM", "REFERENCE, 23.976, 24.01, ORIGINALS",
        "REFERENCE, 23.976, 24.011, FILM", "REFERENCE, 24.0, 24.05, FILM",
        "REFERENCE, 24.99, 25.0, ORIGINALS", "REFERENCE, 24.989, 25.0, FILM", "REFERENCE, 25.01, 25.0, ORIGINALS",
        "REFERENCE, 25.011, 25.0, FILM", "REFERENCE, 23.976, 29.97, FILM", "REFERENCE, 23.976, 25.0, FILM"})
    void theFamilyBandsDecideWhetherTheOriginalsOrTheFilmTimelineAreCompared(String other, double retimedStated,
                                                                           double otherRate, String expected) {
        Side otherSide = "REFERENCE".equals(other) ? rawReference(otherRate)
            : "FILE".equals(other) ? Side.file() : Side.embedded();
        double film = "REFERENCE".equals(other) ? 25.0 : otherRate;
        List<List<String>> compared = new ArrayList<>();

        Judged<String> judged = judge(retimedDownload(retimedStated, "original"), "film", otherSide, "other",
            () -> film, compared);

        assertThat(compared.get(0).equals(List.of("original", "other")) ? "ORIGINALS" : "FILM").isEqualTo(expected);
        // Only a reference pair can be a conflict; any other pair, and any known rate, has a verdict.
        assertThat(judged.reason() == null || "REFERENCE".equals(other)).isTrue();
    }

    @ParameterizedTest(name = "a retimed {0} against {1} while the film rate is unknown")
    @CsvSource({"DOWNLOAD, FILE", "DOWNLOAD, EMBEDDED", "DOWNLOAD, FITS", "REFERENCE, FILE", "REFERENCE, FITS"})
    void aRetimedSideAgainstTheFilmsOwnRateHasNoVerdictWhileTheFilmRateIsUnknown(String retimed, String other) {
        Side retimedSide = "DOWNLOAD".equals(retimed) ? retimedDownload(23.976, "original")
            : retimedReference(23.976, "original");
        Side otherSide = "FILE".equals(other) ? Side.file()
            : "EMBEDDED".equals(other) ? Side.embedded() : rawDownload(24.0, RetimeChoice.OFF_FITS);
        List<List<String>> compared = new ArrayList<>();

        Judged<String> judged = judge(retimedSide, "film", otherSide, "film", FILM_UNKNOWN, compared);

        assertThat(judged.hasVerdict()).isFalse();
        assertThat(judged.reason()).isEqualTo(Reason.UNKNOWN_RATE);
        assertThat(compared).isEmpty();
    }
}
