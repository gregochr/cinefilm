package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.SubtitleStream;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import gregochr.cinefilm.subtitle.SidecarTimingService.FitJob;
import gregochr.cinefilm.subtitle.TimingFit.Result;
import gregochr.cinefilm.subtitle.TimingFit.Verdict;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Fixtures for the tests of downloaded forced subtitles that run against the real services and H2: a film with an
 * IMDb id and OpenSubtitles candidates, stored references, downloads, and current check rows for the owner's file and
 * for a download.
 */
abstract class ForcedDownloadTestBase extends SubtitleTestBase {

    protected static final Instant LATER = NOW.plus(Duration.ofHours(1));
    protected static final String IMDB = "tt0936501";

    @Autowired
    protected ForcedResolver resolver;

    @Autowired
    protected OpenSubtitlesReferenceRepository references;

    @Autowired
    protected ForcedSubtitleCheckRepository checks;

    @Autowired
    protected ForcedDownloadCheckRepository downloadChecks;

    @Autowired
    protected ForcedCheckCandidateRepository ledger;

    @Autowired
    protected ForcedDownloadBlockRepository blocks;

    @Autowired
    protected SidecarTimingService timing;

    @Autowired
    protected EmbeddedCueStartsRepository cache;

    protected static final List<Long> STARTS = IntStream.range(0, 12).mapToObj(i -> 10_000L + i * 7_000L).toList();

    /** One OpenSubtitles search result: a single file. */
    protected static OpenSubtitlesResult result(long fileId, String release) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, release, 23.976, 10, true, false, null, null, 1);
    }

    /** A film with one edition of {@code parts} files, an IMDb id and a search that found these candidates. */
    protected Film searchedFilm(String title, int parts, OpenSubtitlesResult... results) {
        Film film = filmWithParts(title, parts, 60);
        film.setImdbId(IMDB);
        film.setOsSearchedImdbId(IMDB);
        film.setOsForcedCount(results.length);
        film.setOsSearchedAt(NOW);
        film.setOsBest(results[0].toJson());
        film.setOsCandidates(OpenSubtitlesResult.listToJson(List.of(results)));
        return films.saveAndFlush(film);
    }

    protected static Edition edition(Film film) {
        return film.getEditions().get(0);
    }

    /** A stored reference with {@link SubtitleTestBase#CUES} for the film's current IMDb id. */
    protected OpenSubtitlesReference reference(Film film, long fileId) {
        return reference(film, fileId, SubtitleTestBase.CUES, 2);
    }

    protected OpenSubtitlesReference reference(Film film, long fileId, String cues, int cueCount) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setFilmId(film.getId());
        reference.setImdbId(film.getImdbId());
        reference.setOsFileId(fileId);
        reference.setRelease("Release." + fileId);
        reference.setCuesText(cues);
        reference.setCueCount(cueCount);
        reference.setFetchedAt(NOW);
        return references.saveAndFlush(reference);
    }

    /** A download of the film's edition 1 for an OpenSubtitles file, with {@link SubtitleTestBase#CUES}. */
    protected SubtitleSidecar download(Film film, long osFileId) {
        SubtitleSidecar row = new SubtitleSidecar();
        row.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
        row.setPath("opensubtitles:" + edition(film).getId() + ":" + osFileId);
        row.setSizeBytes(100);
        row.setModifiedAt(NOW);
        row.setMediaFileId(edition(film).originalParts().get(0).getId());
        row.setWholeEdition(true);
        row.setCuesText(SubtitleTestBase.CUES);
        row.setCueCount(2);
        row.setLastEndMs(22_500);
        row.setParseReport("Downloaded from OpenSubtitles");
        row.setOpenSubtitlesFileId(osFileId);
        row.setRelease("Release." + osFileId);
        row.setImdbId(film.getImdbId());
        row.setDownloadedAt(NOW);
        row.setUpdatedAt(NOW);
        return sidecars.saveAndFlush(row);
    }

    /** The served version of the owner's files as playback would pick them for the film's edition. */
    protected String fileVersion(Film film) {
        List<Long> partIds = partIds(film);
        Pick pick = SidecarPrecedence.pick(partIds,
            sidecars.findByMediaFileIdInAndKind(partIds, SubtitleKind.FORCED), SubtitleKind.FORCED);
        return ServedSubtitles.version(edition(film), pick);
    }

    protected String downloadVersion(Film film, SubtitleSidecar download) {
        return ServedSubtitles.version(edition(film), Downloads.pick(download));
    }

    private static List<Long> partIds(Film film) {
        return edition(film).originalParts().stream().map(MediaFile::getId).toList();
    }

    /** A current comparison of the owner's files with a reference. */
    protected ForcedSubtitleCheck fileCheck(Film film, String verdict, OpenSubtitlesReference reference) {
        ForcedSubtitleCheck row = new ForcedSubtitleCheck();
        row.setEditionId(edition(film).getId());
        fill(row, film, fileVersion(film), verdict, reference);
        return checks.saveAndFlush(row);
    }

    /** A current comparison of a download with another file. */
    protected ForcedDownloadCheck downloadCheck(Film film, SubtitleSidecar download, String verdict,
                                                OpenSubtitlesReference reference) {
        ForcedDownloadCheck row = new ForcedDownloadCheck();
        row.setSidecarId(download.getId());
        fill(row, film, downloadVersion(film, download), verdict, reference);
        return downloadChecks.saveAndFlush(row);
    }

    private static void fill(SubtitleCheckBase row, Film film, String version, String verdict,
                             OpenSubtitlesReference reference) {
        row.setServedVersion(version);
        row.setReferenceId(reference.getId());
        row.setVerdict(verdict);
        row.setMatchedShare("AGREES".equals(verdict) ? 1.0 : 0.2);
        row.setServedCount(12);
        row.setReferenceCount(12);
        row.setBestOffsetMs(0);
        row.setReasons("AGREES".equals(verdict) ? "" : "OpenSubtitles has 41 lines, this file has 12");
        row.setCheckedAt(LATER);
        // The comparison is for the film's best file as it is now; a search that ranks another first makes it stale.
        row.setBestFileId(OpenSubtitlesResult.fromJson(film.getOsBest()).fileId());
    }

    protected Resolution resolve(Film film) {
        return resolver.resolve(film.getEditions()).get(edition(film).getId());
    }

    /** A forced English text track inside the film's first file. */
    protected void embeddedForced(Film film) {
        MediaFile file = edition(film).originalParts().get(0);
        SubtitleStream stream = new SubtitleStream();
        stream.setMediaFile(file);
        stream.setIndex(10);
        stream.setLanguage("eng");
        stream.setForced(true);
        stream.setFormat("subrip");
        file.getSubtitleStreams().add(stream);
        mediaFiles.saveAndFlush(file);
    }

    /** Gives the film's files a fingerprint, a read of the embedded track, and a stored fit of one subtitle. */
    protected void fitted(Film film, SubtitleSidecar sidecar, Verdict verdict) {
        for (MediaFile file : edition(film).originalParts()) {
            file.setSizeBytes(1_000L + file.getId());
            file.setModifiedAt(NOW);
            mediaFiles.saveAndFlush(file);
            EmbeddedCueStarts read = new EmbeddedCueStarts();
            read.setMediaFileId(file.getId());
            read.setFingerprint(TimingKeys.fingerprint(file));
            read.setStatus(EmbeddedCueStarts.Status.USABLE);
            read.setStreamIndex(2);
            read.setCodec("subrip");
            read.setSubtitleStreamCount(3);
            read.setStartsMs(STARTS);
            read.setReadAt(NOW);
            cache.saveAndFlush(read);
        }
        FitJob job = timing.fitJobs(film.getId()).stream().filter(j -> j.sidecarId().equals(sidecar.getId()))
            .findFirst().orElseThrow();
        timing.save(job, new Result(verdict, 0, verdict == Verdict.ALIGNED ? 1.0 : 0.1, 12, 12, 10, List.of()),
            LATER);
    }
}
