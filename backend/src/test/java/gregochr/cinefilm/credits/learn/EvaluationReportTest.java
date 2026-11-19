package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.learn.CreditsCalibration.Choice;
import gregochr.cinefilm.credits.learn.CreditsCalibration.Counts;
import gregochr.cinefilm.credits.learn.CreditsSegmenter.Verdict;
import gregochr.cinefilm.credits.learn.EvaluationReport.Row;
import gregochr.cinefilm.credits.learn.FilmScore.Band;
import gregochr.cinefilm.credits.learn.FilmScore.Result;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class EvaluationReportTest {

    private static Result found(long early, long goal, FrameKind opening, Band band) {
        return new Result(Verdict.FOUND, OptionalLong.of(1000), OptionalLong.of(2000), Optional.of(true),
            Optional.of(false), OptionalLong.of(early), OptionalLong.of(goal), Optional.of(opening),
            Optional.of(band), 50, 0, false);
    }

    private static Result other(Verdict verdict, OptionalLong t) {
        return new Result(verdict, t, OptionalLong.empty(), Optional.empty(), Optional.empty(), OptionalLong.empty(),
            OptionalLong.empty(), t.isPresent() ? Optional.of(FrameKind.LIT) : Optional.empty(), Optional.empty(),
            Double.NaN, 0, false);
    }

    private static Row row(long id, Result result, FrameKind plex) {
        return new Row(id, Side.DEV, 3, result, Optional.ofNullable(plex), null, null, OptionalLong.empty(), "r");
    }

    @Test
    void aFoundRowWritesEveryColumnAndAnUnfoundRowLeavesTheFoundOnlyColumnsEmpty() {
        Row full = new Row(7, Side.TEST, null, found(-5001, -300, FrameKind.PLATE, Band.HIGH),
            Optional.of(FrameKind.DARK), true, "FOUND", OptionalLong.of(-40), "train-x");
        Row unsure = new Row(8, Side.DEV, 0, other(Verdict.UNSURE, OptionalLong.of(900)), Optional.empty(), false,
            null, OptionalLong.empty(), "train-x");

        assertThat(EvaluationReport.csv(List.of(full, unsure))).isEqualTo(EvaluationReport.HEADER
            + "7,test,,FOUND,1000,2000,true,false,-5001,-300,PLATE,high,DARK,FOUND,-40,train-x,true\n"
            + "8,dev,0,UNSURE,900,,,,,,LIT,,,,,train-x,false\n");
    }

    @Test
    void theHeaderIsExactlyTheSpecifiedColumns() {
        assertThat(EvaluationReport.HEADER).isEqualTo("mediaFileId,side,fold,verdict,t,f,covered,aligned,earlyError,"
            + "goalError,openingAtT,marginBand,plexOpening,v2Verdict,v2Error,run,leadingIsland\n");
    }

    @Test
    void earlyCountsAreByTheOpeningAtTAndTheBandOverFoundFilmsAtTheirBoundaries() {
        JsonNode block = EvaluationReport.block(List.of(
            row(1, found(-15_001, 0, FrameKind.DARK, Band.LOW), null),
            row(2, found(-15_000, 0, FrameKind.DARK, Band.LOW), null),
            row(3, found(-5_001, 0, FrameKind.DARK, Band.LOW), null),
            row(4, found(-5_000, 0, FrameKind.DARK, Band.HIGH), null),
            row(5, found(-20_000, 0, FrameKind.LIT, Band.HIGH), null),
            row(6, other(Verdict.UNSURE, OptionalLong.of(5)), null),
            row(7, other(Verdict.NOT_FOUND, OptionalLong.empty()), null)));

        assertThat(block.get("films").intValue()).isEqualTo(7);
        assertThat(block.get("early").get("DARK").get("low").toString())
            .isEqualTo("{\"found\":3,\"earlyBelow15s\":1,\"earlyBelow5s\":3}");
        assertThat(block.get("early").get("DARK").get("high").toString())
            .isEqualTo("{\"found\":1,\"earlyBelow15s\":0,\"earlyBelow5s\":0}");
        assertThat(block.get("early").get("LIT").get("high").toString())
            .isEqualTo("{\"found\":1,\"earlyBelow15s\":1,\"earlyBelow5s\":1}");
        assertThat(block.get("early").get("PLATE").get("low").toString())
            .isEqualTo("{\"found\":0,\"earlyBelow15s\":0,\"earlyBelow5s\":0}");
    }

    @Test
    void goalSharesAreByPlexsOpeningTypeWithANoneGroupAndNullSharesForAnEmptyGroup() {
        JsonNode goals = EvaluationReport.block(List.of(
            row(1, found(0, 10_000, FrameKind.LIT, Band.LOW), FrameKind.DARK),
            row(2, found(0, -10_001, FrameKind.LIT, Band.LOW), FrameKind.DARK),
            row(3, found(0, 60_000, FrameKind.LIT, Band.LOW), FrameKind.DARK),
            row(4, found(0, -60_001, FrameKind.LIT, Band.LOW), FrameKind.DARK),
            row(5, other(Verdict.UNSURE, OptionalLong.of(5)), FrameKind.DARK),
            row(6, found(0, 0, FrameKind.LIT, Band.LOW), null),
            row(7, other(Verdict.NOT_FOUND, OptionalLong.empty()), null))).get("goals");

        assertThat(goals.get("DARK").toString()).isEqualTo("{\"films\":5,\"within10s\":1,"
            + "\"within60sOrNotFound\":4,\"share10s\":0.2,\"share60sOrNotFound\":0.8}");
        assertThat(goals.get("none").toString()).isEqualTo("{\"films\":2,\"within10s\":1,"
            + "\"within60sOrNotFound\":2,\"share10s\":0.5,\"share60sOrNotFound\":1.0}");
        assertThat(goals.get("PLATE").toString()).isEqualTo("{\"films\":0,\"within10s\":0,"
            + "\"within60sOrNotFound\":0,\"share10s\":null,\"share60sOrNotFound\":null}");
    }

    @Test
    void calibrationHoldsTheGridEachFoldsChoiceAndTheFinalChoice() {
        Counts counts = new Counts(1, 2, 3, 4, 5, 6);
        List<Choice> outer = List.of(0, 1, 2, 3, 4).stream()
            .map(k -> new Choice(k % 2, 0, CreditsCalibration.params(0), counts)).toList();
        Choice last = new Choice(1, CreditsCalibration.POINTS - 1,
            CreditsCalibration.params(CreditsCalibration.POINTS - 1), counts);

        JsonNode json = EvaluationReport.calibration(List.of("a", "b"), outer, last, "sha",
            new CreditsTrainJob.Hashes("d", "l"));

        assertThat(json.get("grid").get("gapFrames").toString()).isEqualTo("[2,3,4,6]");
        assertThat(json.get("grid").get("lambda").toString()).isEqualTo("[0.0,0.5,1.0,2.0,3.0,4.0,5.0]");
        assertThat(json.get("grid").get("delta").toString()).isEqualTo("[0.0,2.0,5.0,10.0,15.0,20.0]");
        assertThat(json.get("grid").get("theta").toString()).isEqualTo("[0.5,0.7,0.8,0.9,0.95]");
        assertThat(json.get("grid").get("sigma").toString())
            .isEqualTo("[5.0,10.0,15.0,20.0,30.0,40.0,60.0,80.0,120.0,160.0]");
        assertThat(json.get("grid").get("thetaDense").toString()).isEqualTo("[0.5,0.7,0.8,0.9,0.95]");
        assertThat(json.get("grid").get("points").intValue()).isEqualTo(42_000);
        assertThat(json.get("outer").get("3").get("run").stringValue()).isEqualTo("b");
        assertThat(json.get("outer").get("4").get("run").stringValue()).isEqualTo("a");
        assertThat(json.get("outer").get("0").get("params").toString()).isEqualTo("{\"lambda\":0.0,\"delta\":0.0,"
            + "\"gapFrames\":2,\"sigma\":5.0,\"theta\":0.5,\"thetaDense\":0.5}");
        assertThat(json.get("final").get("params").toString()).isEqualTo("{\"lambda\":5.0,\"delta\":20.0,"
            + "\"gapFrames\":6,\"sigma\":160.0,\"theta\":0.95,\"thetaDense\":0.95}");
        assertThat(json.get("final").get("counts").toString()).isEqualTo("{\"early15\":1,\"early5\":2,"
            + "\"violations\":3,\"within10\":4,\"within60\":5,\"films\":6}");
        assertThat(json.get("toolGitCommit").stringValue()).isEqualTo("sha");
        assertThat(json.get("datasetSha256").stringValue()).isEqualTo("d");
        assertThat(json.get("labelsSha256").stringValue()).isEqualTo("l");
    }
}
