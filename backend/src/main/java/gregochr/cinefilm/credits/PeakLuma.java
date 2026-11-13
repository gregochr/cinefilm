package gregochr.cinefilm.credits;

import java.util.List;

/**
 * The brightest luma a window of frames really reaches: the 99.5th percentile inside the active area, never below a
 * floor. Every threshold of the analysis is relative to it, so an HDR (PQ) source, whose white text lands around
 * 140 to 150 after the grey conversion, behaves like an SDR one.
 */
public final class PeakLuma {

    private PeakLuma() {
    }

    /**
     * Measures the peak.
     *
     * @param frames the window's frames (all the same size as the area's frame)
     * @param area the picture rectangle to measure in
     * @return the peak luma, at least 60
     */
    public static int of(List<GreyFrame> frames, ActiveArea area) {
        long[] histogram = new long[256];
        long total = 0;
        for (GreyFrame frame : frames) {
            for (int y = area.top(); y < area.top() + area.height(); y++) {
                for (int x = area.left(); x < area.left() + area.width(); x++) {
                    histogram[frame.luma(x, y)]++;
                    total++;
                }
            }
        }
        if (total == 0) {
            return CreditsTuning.PEAK_FLOOR;
        }
        long target = (long) Math.ceil(total * CreditsTuning.PEAK_PERCENTILE);
        long seen = 0;
        int value = 0;
        while (value < 255) {
            seen += histogram[value];
            if (seen >= target) {
                break;
            }
            value++;
        }
        return Math.max(value, CreditsTuning.PEAK_FLOOR);
    }
}
