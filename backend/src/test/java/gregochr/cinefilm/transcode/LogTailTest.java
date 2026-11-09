package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogTailTest {

    @Test
    void anEmptyTailIsEmptyText() {
        assertThat(new LogTail().text()).isEmpty();
    }

    @Test
    void keepsTheLastFortyLinesOldestFirst() {
        LogTail tail = new LogTail();
        for (int i = 1; i <= 41; i++) {
            tail.add("line " + i);
        }

        String[] kept = tail.text().split("\n");
        assertThat(kept).hasSize(40);
        assertThat(kept[0]).isEqualTo("line 2");
        assertThat(kept[39]).isEqualTo("line 41");
    }

    @Test
    void exactlyFortyLinesAreAllKept() {
        LogTail tail = new LogTail();
        for (int i = 1; i <= 40; i++) {
            tail.add("line " + i);
        }

        assertThat(tail.text().split("\n")[0]).isEqualTo("line 1");
    }

    @Test
    void aLongLineIsCutAtFiveHundredCharacters() {
        LogTail tail = new LogTail();
        tail.add("x".repeat(501));
        tail.add("y".repeat(500));

        String[] kept = tail.text().split("\n");
        assertThat(kept[0]).hasSize(500);
        assertThat(kept[1]).hasSize(500);
    }
}
