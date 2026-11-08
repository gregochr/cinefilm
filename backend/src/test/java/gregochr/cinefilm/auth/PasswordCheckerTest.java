package gregochr.cinefilm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class PasswordCheckerTest {

    @Test
    void matchesOnlyTheConfiguredPassword() {
        PasswordChecker checker = new PasswordChecker("s3cret");
        assertThat(checker.matches("s3cret")).isTrue();
        assertThat(checker.matches("s3cret ")).isFalse();
        assertThat(checker.matches("S3CRET")).isFalse();
        assertThat(checker.matches("")).isFalse();
        assertThat(checker.matches(null)).isFalse();
    }

    @Test
    void blankConfigurationFallsBackToChangeme() {
        for (String configured : new String[] {null, "", "   "}) {
            PasswordChecker checker = new PasswordChecker(configured);
            assertThat(checker.matches("changeme")).isTrue();
            assertThat(checker.matches("other")).isFalse();
        }
    }

    @Test
    void theFallbackPasswordIsTheDocumentedDevelopmentOne() {
        assertThat(PasswordChecker.FALLBACK).isEqualTo("changeme");
    }

    @Test
    void usingTheFallbackWarnsLoudlyThatItIsInsecure(CapturedOutput output) {
        new PasswordChecker("").warnIfFallback();

        assertThat(output.getOut()).contains("CINEFILM_PASSWORD is not set")
            .contains("insecure development password");
    }

    @Test
    void aConfiguredPasswordDoesNotWarnAndIsNeverLogged(CapturedOutput output) {
        new PasswordChecker("real-secret").warnIfFallback();

        assertThat(output.getAll()).doesNotContain("CINEFILM_PASSWORD").doesNotContain("real-secret");
    }

    @Test
    void theFallbackIsNotAcceptedOnceARealPasswordIsConfigured() {
        assertThat(new PasswordChecker("real").matches("changeme")).isFalse();
    }
}
