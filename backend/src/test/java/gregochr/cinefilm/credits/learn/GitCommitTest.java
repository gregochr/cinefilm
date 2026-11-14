package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The commit written into {@code complete.json}: the Docker build's key, the Maven plugin's key, or "unknown". */
class GitCommitTest {

    private static InputStream properties(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void theDockerBuildsFullCommitIdIsUsed() {
        assertThat(GitCommit.read(properties("git.commit.id.full=0123456789abcdef\ngit.commit.id.abbrev=0123456\n")))
            .isEqualTo("0123456789abcdef");
    }

    @Test
    void theFullKeyWinsOverTheShortOne() {
        assertThat(GitCommit.read(properties("git.commit.id=short\ngit.commit.id.full=long\n"))).isEqualTo("long");
    }

    @Test
    void theMavenPluginsCommitIdIsUsedWhenThereIsNoFullKey() {
        assertThat(GitCommit.read(properties("git.commit.id=fedcba9876543210\ngit.commit.time=now\n")))
            .isEqualTo("fedcba9876543210");
    }

    @Test
    void aFileWithNeitherKeyIsUnknown() {
        assertThat(GitCommit.read(properties("git.branch=main\n"))).isEqualTo("unknown");
    }

    @Test
    void aBlankCommitIsUnknown() {
        assertThat(GitCommit.read(properties("git.commit.id.full=  \n"))).isEqualTo("unknown");
    }

    @Test
    void aMissingFileIsUnknown() {
        assertThat(GitCommit.read(null)).isEqualTo("unknown");
    }

    @Test
    void aFileThatCannotBeReadIsUnknown() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk error");
            }
        };

        assertThat(GitCommit.read(broken)).isEqualTo("unknown");
    }

    @Test
    void theBuildsOwnCommitIsNeverBlank() {
        assertThat(new GitCommit().commit()).isNotBlank();
    }
}
