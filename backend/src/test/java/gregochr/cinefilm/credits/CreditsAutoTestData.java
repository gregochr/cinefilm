package gregochr.cinefilm.credits;

import static gregochr.cinefilm.TestData.NOW;

import gregochr.cinefilm.credits.CreditsDenyFile.Parsed;
import gregochr.cinefilm.credits.learn.CreditsSplit;
import gregochr.cinefilm.credits.learn.Side;
import java.util.ArrayList;
import java.util.List;

/** Builders shared by the tests of the credits AUTO wiring: tmdb ids on a chosen side, detection rows, deny files. */
public final class CreditsAutoTestData {

    /** The header date every built deny file carries. */
    public static final String MANIFEST_DATE = "2026-11-13";

    private CreditsAutoTestData() {
    }

    /** The {@code n}th (from 1) TMDB id whose unit key {@code tmdb:<id>} the hash puts on the given side. */
    public static long tmdbOn(Side side, int n) {
        long found = 0;
        for (long id = 1; ; id++) {
            if (CreditsSplit.hashSide("tmdb:" + id) == side && ++found == n) {
                return id;
            }
        }
    }

    /** A version 2 {@code FOUND} crawl row for a file. */
    public static CreditsDetection crawl(long mediaFileId, String fingerprint, long offsetMs) {
        CreditsDetection row = new CreditsDetection();
        row.setMediaFileId(mediaFileId);
        row.setFingerprint(fingerprint);
        row.setAnalysisVersion(CreditsApply.AUTO_ANALYSIS_VERSION);
        row.setVerdict(CreditsVerdict.FOUND);
        row.setOffsetMs(offsetMs);
        row.setReasons(CreditsFinder.CRAWL_OPENING + "01:23:20");
        row.setCheckedAt(NOW);
        return row;
    }

    /** A well-formed deny file with these entry lines and the right count. */
    public static Parsed denyFile(String... entries) {
        List<String> lines = new ArrayList<>();
        lines.add("# cinefilm-deny " + MANIFEST_DATE + " " + entries.length);
        lines.addAll(List.of(entries));
        return CreditsDenyFile.parse(lines).orElseThrow();
    }
}
