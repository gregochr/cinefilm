package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SideTest {

    @Test
    void theJsonWordsAreDevAndTest() {
        assertThat(Side.DEV.json()).isEqualTo("dev");
        assertThat(Side.TEST.json()).isEqualTo("test");
    }

    @Test
    void writesAndReadsTheLowerCaseWord() {
        JsonMapper mapper = JsonMapper.builder().build();

        assertThat(mapper.writeValueAsString(Side.DEV)).isEqualTo("\"dev\"");
        assertThat(mapper.readValue("\"test\"", Side.class)).isEqualTo(Side.TEST);
    }
}
