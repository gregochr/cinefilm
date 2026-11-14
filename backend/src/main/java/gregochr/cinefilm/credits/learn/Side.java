package gregochr.cinefilm.credits.learn;

import com.fasterxml.jackson.annotation.JsonValue;

/** Which side of the train/test split a film is on. */
public enum Side {
    /** Development: used to train and to choose every model and threshold. */
    DEV("dev"),
    /** Test: looked at only twice, by the plan's pre-registered rule. */
    TEST("test");

    private final String json;

    Side(String json) {
        this.json = json;
    }

    /** The word written to JSON. */
    @JsonValue
    public String json() {
        return json;
    }
}
