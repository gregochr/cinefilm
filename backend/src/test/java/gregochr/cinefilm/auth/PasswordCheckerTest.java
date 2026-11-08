package gregochr.cinefilm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
            checker.warnIfFallback();
            assertThat(checker.matches("changeme")).isTrue();
            assertThat(checker.matches("other")).isFalse();
        }
    }

    @Test
    void configuredPasswordDoesNotWarn() {
        PasswordChecker checker = new PasswordChecker("real");
        checker.warnIfFallback();
        assertThat(checker.matches("changeme")).isFalse();
    }
}
