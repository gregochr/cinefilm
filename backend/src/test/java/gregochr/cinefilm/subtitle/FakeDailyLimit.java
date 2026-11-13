package gregochr.cinefilm.subtitle;

/** A {@link DailyDownloadLimit} a test sets directly, and may change between runs. */
final class FakeDailyLimit extends DailyDownloadLimit {

    private int value;

    FakeDailyLimit(int value) {
        super(null, 0);
        this.value = value;
    }

    void set(int newValue) {
        this.value = newValue;
    }

    @Override
    public int today() {
        return value;
    }
}
