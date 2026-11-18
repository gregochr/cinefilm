package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The window is [0, 400000): film [0, 95000), BOUNDARY [95000, 105000), credits [105000, 295000), BOUNDARY
 * [295000, 305000), AFTER_LAST_CREDITS [305000, 400000); the target is 100000. Every id and number is made up.
 */
class FrameLabelsTest {

    private static final long WINDOW_END = 400_000;
    private static final long TARGET = 100_000;

    private static final LeftOut BOUNDARY_IN = new LeftOut(95_000, 105_000, LeftOutReason.BOUNDARY);
    private static final LeftOut BOUNDARY_OUT = new LeftOut(295_000, 305_000, LeftOutReason.BOUNDARY);
    private static final LeftOut AFTER = new LeftOut(305_000, WINDOW_END, LeftOutReason.AFTER_LAST_CREDITS);

    private static LeftOut out(long from, long to, LeftOutReason reason) {
        return new LeftOut(from, to, reason);
    }

    private static LabelRecord record(List<Span> credits, List<Span> film, List<LeftOut> leftOut) {
        return new LabelRecord(7, "100:200", "unit-7", Side.DEV, 2, TARGET, 0L, false, credits, film, leftOut, null,
            null);
    }

    private static LabelRecord base() {
        return record(List.of(new Span(105_000, 295_000)), List.of(new Span(0, 95_000)),
            List.of(BOUNDARY_IN, BOUNDARY_OUT, AFTER));
    }

    private static FrameLabelRecord expected(LabelRecord from, List<Span> credits, List<LeftOut> leftOut,
                                             FrameKind type) {
        return new FrameLabelRecord(from.mediaFileId(), from.fingerprint(), from.unitKey(), from.side(), from.fold(),
            from.targetStartMs(), from.shiftMs(), from.leadingIsland(), credits, from.film(), leftOut, null, null,
            type, type == null ? null : type == FrameKind.LIT);
    }

    /** One kind per character ({@code D}, {@code P}, {@code L}), every 10 s from {@code firstMs}. */
    private static List<KindAt> kinds(long firstMs, String chars) {
        List<KindAt> list = new ArrayList<>();
        for (int i = 0; i < chars.length(); i++) {
            list.add(frame(firstMs + i * 10_000L, chars.charAt(i)));
        }
        return list;
    }

    private static KindAt frame(long offsetMs, char kind) {
        FrameKind frameKind = FrameKind.LIT;
        if (kind == 'D') {
            frameKind = FrameKind.DARK;
        } else if (kind == 'P') {
            frameKind = FrameKind.PLATE;
        }
        return new KindAt(offsetMs, frameKind);
    }

    /** Disjoint, covering {@code [windowStart, duration)}, no empty stretch, left_out sorted. */
    private static void assertInvariants(FrameLabelRecord r, long windowStart, long duration) {
        List<long[]> all = new ArrayList<>();
        r.credits().forEach(s -> all.add(new long[] {s.fromMs(), s.toMs()}));
        r.film().forEach(s -> all.add(new long[] {s.fromMs(), s.toMs()}));
        r.leftOut().forEach(s -> all.add(new long[] {s.fromMs(), s.toMs()}));
        all.sort((a, b) -> Long.compare(a[0], b[0]));
        long cursor = windowStart;
        for (long[] span : all) {
            assertThat(span[0]).as("start of %d-%d", span[0], span[1]).isEqualTo(cursor);
            assertThat(span[1]).as("end of %d-%d", span[0], span[1]).isGreaterThan(span[0]);
            cursor = span[1];
        }
        assertThat(cursor).isEqualTo(duration);
        assertThat(r.leftOut()).extracting(LeftOut::fromMs).isSorted();
    }

    private static FrameLabelRecord applied(LabelRecord record, List<KindAt> coarse) {
        FrameLabelRecord result = FrameLabels.apply(record, coarse);
        assertInvariants(result, 0, WINDOW_END);
        return result;
    }

    @Test
    void aPlateFilmKeepsEveryPlateFrameAsCredits() {
        LabelRecord record = base();

        FrameLabelRecord result = applied(record, kinds(100_000, "PPPPPPPPPPPPPPPPPPPP"));

        assertThat(result).isEqualTo(expected(record, record.credits(), record.leftOut(), FrameKind.PLATE));
    }

    @Test
    void aLitOpeningIsLeftOutUpToTheFirstPlainPair() {
        LabelRecord record = base();

        FrameLabelRecord result = applied(record, kinds(100_000, "LLLDDDDDDDDDDDDDDDDD"));

        assertThat(result).isEqualTo(expected(record, List.of(new Span(130_000, 295_000)),
            List.of(BOUNDARY_IN, out(105_000, 130_000, LeftOutReason.LIT_OPENING), BOUNDARY_OUT, AFTER),
            FrameKind.LIT));
        assertThat(result.litOpening()).isTrue();
    }

    @Test
    void anIntervalWithNoPlainPairIsLeftOutWhole() {
        LabelRecord record = base();

        FrameLabelRecord result = applied(record, kinds(100_000, "LDLDLDLDLDLDLDLDLDLD"));

        assertThat(result).isEqualTo(expected(record, List.of(),
            List.of(BOUNDARY_IN, out(105_000, 295_000, LeftOutReason.LIT_OPENING), BOUNDARY_OUT, AFTER),
            FrameKind.LIT));
    }

    @Test
    void aLoneDarkFrameBeforeThePairDoesNotEndTheOpening() {
        LabelRecord record = base();

        // 110000 P alone, 120000 L, then the pair at 130000 and 140000
        FrameLabelRecord result = applied(record, kinds(100_000, "LPLDDDDDDDDDDDDDDDDD"));

        assertThat(result).isEqualTo(expected(record, List.of(new Span(130_000, 295_000)),
            List.of(BOUNDARY_IN, out(105_000, 130_000, LeftOutReason.LIT_OPENING), BOUNDARY_OUT, AFTER),
            FrameKind.LIT));
    }

    @Test
    void aPairAtTheFirstFrameLeavesNothingOutEvenWithLitBeforeTheInterval() {
        LabelRecord record = base();
        // 100000 is before the interval (ignored); 105000 and 115000 are the pair, the first at the interval's start
        List<KindAt> coarse = new ArrayList<>(List.of(frame(100_000, 'L'), frame(105_000, 'P'), frame(115_000, 'P')));
        coarse.addAll(kinds(125_000, "PPPPPPPPPPPPPPPPP"));

        FrameLabelRecord result = applied(record, coarse);

        assertThat(result).isEqualTo(expected(record, record.credits(), record.leftOut(), FrameKind.PLATE));
    }

    @Test
    void aLitRunEndingAtTheIntervalsEndIsCutThere() {
        LabelRecord record = base();

        // lit at 260000..290000; the last + 5000 is 295000, the interval's end
        FrameLabelRecord result = applied(record, kinds(100_000, "PPPPPPPPPPPPPPPPLLLL"));

        assertThat(result).isEqualTo(expected(record, List.of(new Span(105_000, 260_000)),
            List.of(BOUNDARY_IN, out(260_000, 295_000, LeftOutReason.LIT_RUN), BOUNDARY_OUT, AFTER),
            FrameKind.PLATE));
    }

    @Test
    void aLitRunPastTheIntervalsEndIsCutAtTheEnd() {
        LabelRecord record = record(List.of(new Span(105_000, 292_000)), List.of(new Span(0, 95_000)),
            List.of(BOUNDARY_IN, out(292_000, WINDOW_END, LeftOutReason.AFTER_LAST_CREDITS)));

        FrameLabelRecord result = applied(record, kinds(100_000, "PPPPPPPPPPPPPPPPLLLL"));

        assertThat(result).isEqualTo(expected(record, List.of(new Span(105_000, 260_000)),
            List.of(BOUNDARY_IN, out(260_000, 292_000, LeftOutReason.LIT_RUN),
                out(292_000, WINDOW_END, LeftOutReason.AFTER_LAST_CREDITS)), FrameKind.PLATE));
    }

    @Test
    void aRunOfThreeIsCutFiveSecondsAfterItsLastFrameAndTheCreditsAroundItStay() {
        LabelRecord record = base();

        FrameLabelRecord result = applied(record, kinds(100_000, "PPPPPPPPPPLLLPPPPPPP"));

        assertThat(result).isEqualTo(expected(record, List.of(new Span(105_000, 200_000), new Span(225_000, 295_000)),
            List.of(BOUNDARY_IN, out(200_000, 225_000, LeftOutReason.LIT_RUN), BOUNDARY_OUT, AFTER),
            FrameKind.PLATE));
    }

    @Test
    void aRunOfExactlyTwoLitFramesIsKept() {
        LabelRecord record = base();

        FrameLabelRecord result = applied(record, kinds(100_000, "PPPPPPPPPPLLPPPPPPPP"));

        assertThat(result).isEqualTo(expected(record, record.credits(), record.leftOut(), FrameKind.PLATE));
    }

    @Test
    void aRunOfTwoAtTheEndIsKeptEvenWhenTheLitContinuesPastTheInterval() {
        LabelRecord record = base();
        // lit at 280000 and 290000 inside; 295000 is the interval's end (not inside); 300000 and 310000 are past it
        List<KindAt> coarse = new ArrayList<>(kinds(100_000, "PPPPPPPPPPPPPPPPPPL"));
        coarse.add(frame(290_000, 'L'));
        coarse.add(frame(295_000, 'L'));
        coarse.add(frame(300_000, 'L'));

        FrameLabelRecord result = applied(record, coarse);

        assertThat(result).isEqualTo(expected(record, record.credits(), record.leftOut(), FrameKind.PLATE));
    }

    @Test
    void closelySpacedRunsDoNotOverlapAndAreJoined() {
        LabelRecord record = base();
        List<KindAt> coarse = List.of(frame(110_000, 'P'), frame(112_000, 'P'), frame(114_000, 'L'),
            frame(116_000, 'L'), frame(118_000, 'L'), frame(120_000, 'P'), frame(122_000, 'L'), frame(124_000, 'L'),
            frame(126_000, 'L'), frame(128_000, 'P'));

        FrameLabelRecord result = applied(record, coarse);

        assertThat(result).isEqualTo(expected(record, List.of(new Span(105_000, 114_000), new Span(131_000, 295_000)),
            List.of(BOUNDARY_IN, out(114_000, 131_000, LeftOutReason.LIT_RUN), BOUNDARY_OUT, AFTER),
            FrameKind.PLATE));
    }

    @Test
    void aSecondRunInsideAClampedRunsTailAddsNothing() {
        LabelRecord record = base();
        List<KindAt> coarse = List.of(frame(110_000, 'P'), frame(120_000, 'P'), frame(290_000, 'L'),
            frame(291_000, 'L'), frame(292_000, 'L'), frame(293_000, 'P'), frame(293_500, 'L'), frame(294_000, 'L'),
            frame(294_500, 'L'));

        FrameLabelRecord result = applied(record, coarse);

        assertThat(result).isEqualTo(expected(record, List.of(new Span(105_000, 290_000)),
            List.of(BOUNDARY_IN, out(290_000, 295_000, LeftOutReason.LIT_RUN), BOUNDARY_OUT, AFTER),
            FrameKind.PLATE));
    }

    @Test
    void touchingLeftOutStretchesOfOneReasonAreJoinedFromTheFirstEntryOn() {
        LabelRecord record = record(List.of(new Span(105_000, 295_000)), List.of(new Span(0, 105_000)),
            List.of(out(295_000, 350_000, LeftOutReason.AFTER_LAST_CREDITS),
                out(350_000, WINDOW_END, LeftOutReason.AFTER_LAST_CREDITS)));

        FrameLabelRecord result = applied(record, kinds(100_000, "PPPPPPPPPPPPPPPPPPPP"));

        assertThat(result.leftOut()).containsExactly(out(295_000, WINDOW_END, LeftOutReason.AFTER_LAST_CREDITS));
    }

    @Test
    void anIntervalHoldingOneCoarseFrameIsLeftOutWhole() {
        LabelRecord record = record(List.of(new Span(105_000, 115_000)), List.of(new Span(0, 95_000)),
            List.of(BOUNDARY_IN, out(115_000, 125_000, LeftOutReason.BOUNDARY),
                out(125_000, WINDOW_END, LeftOutReason.AFTER_LAST_CREDITS)));

        FrameLabelRecord result = applied(record, kinds(100_000, "PPPPPPPPPP"));

        assertThat(result).isEqualTo(expected(record, List.of(),
            List.of(BOUNDARY_IN, out(105_000, 115_000, LeftOutReason.LIT_OPENING),
                out(115_000, 125_000, LeftOutReason.BOUNDARY),
                out(125_000, WINDOW_END, LeftOutReason.AFTER_LAST_CREDITS)), FrameKind.PLATE));
    }

    @Test
    void aFilmWithNoCoarseFrameAtOrAfterTheTargetStillGetsItsLabelsButNoFlags() {
        LabelRecord record = base();

        FrameLabelRecord result = applied(record, kinds(40_000, "PDP"));

        assertThat(result).isEqualTo(expected(record, List.of(),
            List.of(BOUNDARY_IN, out(105_000, 295_000, LeftOutReason.LIT_OPENING), BOUNDARY_OUT, AFTER), null));
        assertThat(result.openingType()).isNull();
        assertThat(result.litOpening()).isNull();
    }

    @Test
    void anEmptyCoarseListLeavesTheWholeIntervalOutWithNoFlags() {
        LabelRecord record = base();

        FrameLabelRecord result = applied(record, List.of());

        assertThat(result).isEqualTo(expected(record, List.of(),
            List.of(BOUNDARY_IN, out(105_000, 295_000, LeftOutReason.LIT_OPENING), BOUNDARY_OUT, AFTER), null));
    }

    @Test
    void severalCreditsIntervalsAreEachTreatedOnTheirOwn() {
        LabelRecord record = record(List.of(new Span(105_000, 145_000), new Span(205_000, 295_000)),
            List.of(new Span(0, 95_000), new Span(155_000, 195_000)),
            List.of(BOUNDARY_IN, out(145_000, 155_000, LeftOutReason.BOUNDARY),
                out(195_000, 205_000, LeftOutReason.BOUNDARY), BOUNDARY_OUT, AFTER));

        FrameLabelRecord result = applied(record, kinds(100_000, "DLLPPLLLLLLPPLLLPPPP"));

        assertThat(result).isEqualTo(expected(record,
            List.of(new Span(130_000, 145_000), new Span(205_000, 230_000), new Span(255_000, 295_000)),
            List.of(BOUNDARY_IN, out(105_000, 130_000, LeftOutReason.LIT_OPENING),
                out(145_000, 155_000, LeftOutReason.BOUNDARY), out(195_000, 205_000, LeftOutReason.BOUNDARY),
                out(230_000, 255_000, LeftOutReason.LIT_RUN), BOUNDARY_OUT, AFTER), FrameKind.DARK));
    }

    @Test
    void anExcludedRecordKeepsItsEmptyListsAndGetsNullFlags() {
        LabelRecord excluded = new LabelRecord(9, "1:2", "unit-9", Side.TEST, null, null, null, null, List.of(),
            List.of(), List.of(), ExcludeReason.NO_ARCHIVE_ITEM, null);

        FrameLabelRecord result = FrameLabels.apply(excluded, kinds(100_000, "LLLLLLLL"));

        assertThat(result).isEqualTo(new FrameLabelRecord(9, "1:2", "unit-9", Side.TEST, null, null, null, null,
            List.of(), List.of(), List.of(), ExcludeReason.NO_ARCHIVE_ITEM, null, null, null));
        assertThatThrownBy(() -> FrameLabels.labelAt(result, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("9");
    }

    // ---- labelAt -------------------------------------------------------------------------------------------------

    @Test
    void labelAtIsHalfOpenAtEveryBoundary() {
        FrameLabelRecord result = applied(base(), kinds(100_000, "PPPPPPPPPPPPPPPPLLLL"));

        assertThat(FrameLabels.labelAt(result, 0)).isEqualTo(FrameLabel.FILM);
        assertThat(FrameLabels.labelAt(result, 94_999)).isEqualTo(FrameLabel.FILM);
        assertThat(FrameLabels.labelAt(result, 95_000)).isEqualTo(FrameLabel.leftOut(LeftOutReason.BOUNDARY));
        assertThat(FrameLabels.labelAt(result, 104_999)).isEqualTo(FrameLabel.leftOut(LeftOutReason.BOUNDARY));
        assertThat(FrameLabels.labelAt(result, 105_000)).isEqualTo(FrameLabel.CREDITS);
        assertThat(FrameLabels.labelAt(result, 259_999)).isEqualTo(FrameLabel.CREDITS);
        assertThat(FrameLabels.labelAt(result, 260_000)).isEqualTo(FrameLabel.leftOut(LeftOutReason.LIT_RUN));
        assertThat(FrameLabels.labelAt(result, 294_500)).isEqualTo(FrameLabel.leftOut(LeftOutReason.LIT_RUN));
        assertThat(FrameLabels.labelAt(result, 295_000)).isEqualTo(FrameLabel.leftOut(LeftOutReason.BOUNDARY));
        assertThat(FrameLabels.labelAt(result, 399_999))
            .isEqualTo(FrameLabel.leftOut(LeftOutReason.AFTER_LAST_CREDITS));
        assertThat(FrameLabel.leftOut(LeftOutReason.LIT_OPENING).kind()).isEqualTo(FrameLabel.Kind.LEFT_OUT);
        assertThat(FrameLabel.CREDITS.reason()).isNull();
    }

    @Test
    void labelAtOutsideTheWindowThrowsOnBothSides() {
        FrameLabelRecord result = applied(base(), kinds(100_000, "PPPPPPPPPPPPPPPPPPPP"));

        assertThatThrownBy(() -> FrameLabels.labelAt(result, -1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("-1");
        assertThatThrownBy(() -> FrameLabels.labelAt(result, WINDOW_END))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("400000");
    }

    @Test
    void labelAtFindsTheLeftOutReasonOfTheLitOpening() {
        FrameLabelRecord result = applied(base(), kinds(100_000, "LLLDDDDDDDDDDDDDDDDD"));

        assertThat(FrameLabels.labelAt(result, 129_999)).isEqualTo(FrameLabel.leftOut(LeftOutReason.LIT_OPENING));
        assertThat(FrameLabels.labelAt(result, 130_000)).isEqualTo(FrameLabel.CREDITS);
    }
}
