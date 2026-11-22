package gregochr.cinefilm.credits.learn;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The command line of the sheet tool.
 *
 * @param candidates the candidates JSON the owner fetched
 * @param out the output directory; each run makes a directory of its own inside it
 * @param media the media root the candidates' paths are relative to
 * @param full when set, write one full-size still instead of the sheets
 */
record AutoCheckOptions(Path candidates, Path out, Path media, Full full) {

    /** The media root inside the container. */
    static final String DEFAULT_MEDIA = "/media";
    /** How many tiles a sheet has. */
    static final int TILES = 9;

    private static final List<String> FLAGS = List.of("candidates", "out", "media");
    private static final String FULL = "full";

    /**
     * One still asked for with {@code --full}.
     *
     * @param editionId the edition
     * @param tile the tile, 1 to 9
     */
    record Full(long editionId, int tile) {
    }

    /**
     * Parses {@code --flag value} pairs and {@code --full <editionId> <tile>}.
     *
     * @param args the arguments
     * @return the options
     * @throws IllegalArgumentException with a one-line message when a flag is missing, repeated, unknown or has no
     *     value, or the edition id or tile is not usable
     */
    static AutoCheckOptions parse(String[] args) {
        Map<String, String> flags = new HashMap<>();
        Full full = null;
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            String flag = name.startsWith("--") ? name.substring(2) : name;
            if (flag.equals(name) || !FLAGS.contains(flag) && !FULL.equals(flag)) {
                throw new IllegalArgumentException("unknown argument " + name);
            }
            if (FULL.equals(flag)) {
                if (full != null) {
                    throw new IllegalArgumentException("flag " + name + " given twice");
                }
                if (i + 2 >= args.length) {
                    throw new IllegalArgumentException("flag " + name + " needs an edition id and a tile");
                }
                full = full(args[i + 1], args[i + 2]);
                i += 2;
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("flag " + name + " needs a value");
            }
            if (flags.put(flag, args[++i]) != null) {
                throw new IllegalArgumentException("flag " + name + " given twice");
            }
        }
        for (String name : List.of("candidates", "out")) {
            if (!flags.containsKey(name)) {
                throw new IllegalArgumentException("missing required flag --" + name);
            }
        }
        return new AutoCheckOptions(Path.of(flags.get("candidates")), Path.of(flags.get("out")),
            Path.of(flags.getOrDefault("media", DEFAULT_MEDIA)), full);
    }

    private static Full full(String edition, String tile) {
        try {
            long editionId = Long.parseLong(edition);
            int tileNumber = Integer.parseInt(tile);
            if (tileNumber >= 1 && tileNumber <= TILES) {
                return new Full(editionId, tileNumber);
            }
        } catch (NumberFormatException e) {
            // Refused below, the same as a tile out of range.
        }
        throw new IllegalArgumentException("--full needs an edition id and a tile from 1 to " + TILES);
    }
}
