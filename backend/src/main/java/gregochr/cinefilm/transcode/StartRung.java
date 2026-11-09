package gregochr.cinefilm.transcode;

import java.util.List;

/** Picks the rung a player should start on from a throughput probe. */
public final class StartRung {

    /** A rung is chosen only when the measured throughput covers its peak bandwidth with this much headroom. */
    static final double HEADROOM = 1.25;

    private StartRung() {
    }

    /**
     * The highest rung whose peak bandwidth times 1.25 fits in the probe, else the lowest rung.
     *
     * @param rungs the film's rungs, best first (never empty)
     * @param probeKbps measured throughput in kilobits per second, or null when no probe ran (the top rung)
     * @return the rung to list first in the master playlist
     */
    public static Rung pick(List<Rung> rungs, Long probeKbps) {
        if (rungs.isEmpty()) {
            throw new IllegalArgumentException("a film has at least one rung");
        }
        if (probeKbps == null) {
            return rungs.get(0);
        }
        double available = probeKbps * 1000.0;
        for (Rung rung : rungs) {
            if (rung.peakBandwidth() * HEADROOM <= available) {
                return rung;
            }
        }
        return rungs.get(rungs.size() - 1);
    }
}
