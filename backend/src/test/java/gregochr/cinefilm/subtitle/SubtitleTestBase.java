package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** Fixtures for the tests of the forced subtitle feature that run against the real services and H2. */
abstract class SubtitleTestBase extends ApiTestBase {

    /** Two cues: 10 s to 12 s and 20 s to 22.5 s. */
    protected static final String CUES = "1\n00:00:10,000 --> 00:00:12,000\nHello\n\n"
        + "2\n00:00:20,000 --> 00:00:22,500\nWorld\n\n";

    @Autowired
    protected SubtitleSidecarRepository sidecars;

    @Autowired
    protected MediaFileRepository mediaFiles;

    @Autowired
    protected EditionRepository editionRepository;

    @Autowired
    protected ForcedSubtitleService forced;

    @Autowired
    protected SubtitleAdminService admin;

    /** A film with one edition of {@code parts} files (one file {@code Title.m4v}, or {@code Title - ptN.m4v}). */
    protected Film filmWithParts(String title, int parts, long partMinutes) {
        Film built = film(null, title, 2000, NOW);
        Edition edition = edition(built, null, "Original", parts * partMinutes * MIN, null);
        for (int i = 1; i <= parts; i++) {
            MediaFile file = file(edition, parts == 1 ? title + ".m4v" : title + " - pt" + i + ".m4v");
            file.setPartNumber(i);
            file.setDurationMs(partMinutes * MIN);
        }
        return save(built);
    }

    protected static Long fileId(Film film, int part) {
        return film.getEditions().get(0).originalParts().get(part - 1).getId();
    }

    /** A present sidecar with {@link #CUES} attached to a film file (null for none). */
    protected SubtitleSidecar sidecar(String path, Long fileId, boolean whole) {
        SubtitleSidecar sidecar = new SubtitleSidecar();
        sidecar.setPath(path);
        sidecar.setSizeBytes(100);
        sidecar.setModifiedAt(NOW);
        sidecar.setMediaFileId(fileId);
        sidecar.setWholeEdition(whole);
        sidecar.setCuesText(CUES);
        sidecar.setParseReport("No problems found");
        sidecar.setCueCount(2);
        sidecar.setLastEndMs(22_500);
        sidecar.setUpdatedAt(NOW);
        return sidecars.save(sidecar);
    }
}
