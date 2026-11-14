package gregochr.cinefilm.health;

import static gregochr.cinefilm.health.HealthSamples.example;
import static gregochr.cinefilm.health.HealthSamples.report;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.health.HealthDtos.HealthReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

class HealthFiguresTest {

    /** The example with every sign of trouble taken out. */
    private static ObjectNode smooth() {
        ObjectNode node = example();
        for (String field : new String[] {"stallCount", "stallMs", "reconnectCount", "stepsDown", "netErrors",
            "timeouts"}) {
            node.put(field, 0);
        }
        node.put("fellBack", false);
        return node;
    }

    @Test
    void theExampleReportGivesItsFigures() {
        HealthFigures figures = HealthFigures.of(report(example()));

        assertThat(figures).isEqualTo(new HealthFigures(171_000, 2, 9_400, 2_380L, true, "train"));
    }

    @Test
    void aReportWithNothingWrongHasNoProblem() {
        assertThat(HealthFigures.of(report(smooth())).hasProblem()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"stallCount", "reconnectCount", "netErrors", "timeouts"})
    void eachCountAboveZeroIsAProblemOnItsOwn(String field) {
        ObjectNode node = smooth();
        node.put(field, 1);

        assertThat(HealthFigures.of(report(node)).hasProblem()).isTrue();
    }

    @Test
    void aStepDownToALowerRungIsNotAProblem() {
        ObjectNode node = smooth();
        node.put("stepsDown", 5);

        assertThat(HealthFigures.of(report(node)).hasProblem()).isFalse();
    }

    @Test
    void fallingBackToDirectPlayIsAProblem() {
        ObjectNode node = smooth();
        node.put("fellBack", true);

        assertThat(HealthFigures.of(report(node)).hasProblem()).isTrue();
    }

    @Test
    void aReportWithoutAFirstFrameHasNoStartupTime() {
        ObjectNode node = smooth();
        node.remove("startupMs");

        assertThat(HealthFigures.of(report(node)).startupMs()).isNull();
    }

    @Test
    void missingFiguresCountAsZeroAndNoProblem() {
        HealthFigures figures = HealthFigures.of(HealthSamples.JSON.readValue("{}", HealthReport.class));

        assertThat(figures).isEqualTo(new HealthFigures(0, 0, 0, null, false, null));
    }

    @Test
    void anEnvironmentLongerThanTheColumnIsNotStored() {
        ObjectNode node = example();
        node.put("environment", "x".repeat(HealthFigures.MAX_ENVIRONMENT + 1));

        assertThat(HealthFigures.of(report(node)).environment()).isNull();
    }

    @Test
    void anEnvironmentAsLongAsTheColumnIsStored() {
        ObjectNode node = example();
        node.put("environment", "x".repeat(HealthFigures.MAX_ENVIRONMENT));

        assertThat(HealthFigures.of(report(node)).environment()).hasSize(HealthFigures.MAX_ENVIRONMENT);
    }
}
