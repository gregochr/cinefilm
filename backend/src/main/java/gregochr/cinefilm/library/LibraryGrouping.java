package gregochr.cinefilm.library;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.GroupingChange;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.film.GroupingSource;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.library.RepairRules.Decision;
import gregochr.cinefilm.library.RepairRules.Kind;
import gregochr.cinefilm.library.RepairRules.TitleGroup;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scan's grouping passes over the whole library, run under the scan lock: <em>repair</em> (split a film whose files
 * are different films, or flag it), <em>group</em> (the auto-resolve: films and editions that belong together are
 * joined) and <em>refresh defaults</em> (the edition a film opens on, when the owner has not chosen one). The deciding
 * is in the pure {@link RepairRules}, {@link EditionGrouper} and {@link DefaultEdition}; the joining in
 * {@link EditionJoin}. Each change is its own transaction and one that fails is logged and left for the next scan; each
 * writes a {@code grouping_change} line (never mentioning part numbers: the report is shown to the owner).
 * Films the owner settled are never read.
 */
@Service
public class LibraryGrouping {

    private static final Logger LOG = LoggerFactory.getLogger(LibraryGrouping.class);
    private static final int NAME_MAX = 255;
    private static final int SUMMARY_MAX = 500;

    private final FilmRepository films;
    private final EditionRepository editions;
    private final MediaFileRepository files;
    private final FilmPreferenceRepository preferences;
    private final GroupingChangeRepository changes;
    private final EditionJoin join;
    private final LibraryIngest ingest;
    private final TransactionTemplate tx;
    private final Clock clock;

    /** Creates the passes over the repositories, the join, the ingest (for film merges), a clock and transactions. */
    public LibraryGrouping(FilmRepository films, EditionRepository editions, MediaFileRepository files,
                           FilmPreferenceRepository preferences, GroupingChangeRepository changes, EditionJoin join,
                           LibraryIngest ingest, PlatformTransactionManager transactions, Clock clock) {
        this.films = films;
        this.editions = editions;
        this.files = files;
        this.preferences = preferences;
        this.changes = changes;
        this.join = join;
        this.ingest = ingest;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** What the grouping passes read: every original file of a film the owner has not settled. */
    List<FileFact> facts() {
        return tx.execute(status -> {
            List<FileFact> facts = new ArrayList<>();
            for (MediaFile file : files.findAllOriginalsWithEdition()) {
                Edition edition = file.getEdition();
                Film film = edition.getFilm();
                if (!film.isEditionsConfirmed()) {
                    facts.add(new FileFact(film.getId(), film.getTitle(), film.getTmdbId(), film.getMatchStatus(),
                        edition.getId(), edition.getName(), edition.getGroupingSource(), file.getId(),
                        file.getPath(), file.getPartNumber(), file.getDurationMs(), file.getPresence(),
                        file.getPlayability(), FileNameParser.parse(file.getPath()),
                        edition.getMediaFiles().stream().anyMatch(f -> f.getKind() != MediaKind.ORIGINAL)));
                }
            }
            return facts;
        });
    }

    /**
     * Library repair: a film whose files have different comparison titles is split on strong evidence (the other titles
     * move whole to a new pending film, which the scan then matches) and otherwise flagged, never guessed at.
     *
     * @return how many films were split or newly flagged
     */
    public int repair() {
        Map<Long, List<FileFact>> byFilm = new LinkedHashMap<>();
        facts().forEach(f -> byFilm.computeIfAbsent(f.filmId(), k -> new ArrayList<>()).add(f));
        int done = 0;
        for (List<FileFact> filmFiles : byFilm.values()) {
            FileFact any = filmFiles.get(0);
            Decision decision = RepairRules.decide(any.filmTitle(), any.filmTmdbId(), filmFiles);
            try {
                if (decision.kind() == Kind.SPLIT) {
                    tx.executeWithoutResult(status -> split(any, filmFiles, decision));
                    done++;
                } else if (decision.kind() == Kind.FLAG && flag(decision.summary())) {
                    done++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Could not repair film {}: {}", any.filmId(), e.getClass().getSimpleName());
            }
        }
        return done;
    }

    /**
     * Auto-resolve: films with the same folder, title and year but different edition tags become one film with several
     * editions; part-tagged files whose numbers run 1..n become one edition.
     *
     * @return how many films were merged and how many editions joined
     */
    public int group() {
        int done = 0;
        renameOriginals();
        List<FileFact> facts = facts();
        List<List<Long>> merges = EditionGrouper.filmMerges(facts);
        for (List<Long> merge : merges) {
            try {
                tx.executeWithoutResult(status -> merge.subList(1, merge.size()).forEach(from ->
                    ingest.merge(from, merge.get(0))));
                log(GroupingChange.JOIN, "Grouped the files of \"" + title(facts, merge.get(0))
                    + "\" into one film with separate editions");
                done++;
            } catch (RuntimeException e) {
                LOG.warn("Could not merge films {}: {}", merge, e.getClass().getSimpleName());
            }
        }
        List<FileFact> current = merges.isEmpty() ? facts : facts();
        Map<Long, FileFact> byFile = new LinkedHashMap<>();
        current.forEach(f -> byFile.put(f.fileId(), f));
        for (List<Long> fileIds : EditionGrouper.partJoins(current)) {
            try {
                join.join(fileIds);
                FileFact first = byFile.get(fileIds.get(0));
                log(GroupingChange.JOIN, "Joined " + fileIds.size() + " files of \"" + first.filmTitle()
                    + "\" into one edition \"" + first.editionName() + "\"");
                done++;
            } catch (RuntimeException e) {
                LOG.warn("Could not join files {}: {}", fileIds, e.getClass().getSimpleName());
            }
        }
        return done;
    }

    /**
     * Files scanned before editions were named from their tags all got the name "Original". An auto-grouped edition of
     * a film the owner has not settled that still has that name takes the edition tag (else the plain edition word) of
     * its first file's name. Idempotent, and nothing is logged.
     *
     * @return how many editions were renamed
     */
    int renameOriginals() {
        Map<Long, FileFact> firsts = new LinkedHashMap<>();
        for (FileFact fact : facts()) {
            firsts.merge(fact.editionId(), fact, (a, b) -> a.partNumber() <= b.partNumber() ? a : b);
        }
        int renamed = 0;
        for (FileFact first : firsts.values()) {
            String name = first.parsed().editionTag() != null ? first.parsed().editionTag()
                : first.parsed().plainEdition();
            if (name != null && "Original".equals(first.editionName())
                && first.editionSource() == GroupingSource.AUTO) {
                tx.executeWithoutResult(status -> editions.findById(first.editionId()).orElseThrow()
                    .setName(cut(name, NAME_MAX)));
                renamed++;
            }
        }
        return renamed;
    }

    /**
     * Sets the edition each multi-edition film opens on, unless the owner has chosen one: an untagged edition or one
     * named "theatrical", else the longest.
     *
     * @return how many films' default changed
     */
    public int refreshDefaults() {
        int[] count = {0};
        tx.executeWithoutResult(status -> {
            for (Film film : films.findWithSeveralEditions()) {
                FilmPreference preference = preferences.findById(film.getId()).orElse(null);
                if (preference != null && preference.isDefaultEditionChosen()) {
                    continue;
                }
                Edition choice = DefaultEdition.choose(film.getEditions()).orElseThrow();
                if (preference == null) {
                    preference = new FilmPreference(film.getId());
                }
                if (!Objects.equals(preference.getDefaultEditionId(), choice.getId())) {
                    preference.setDefaultEditionId(choice.getId());
                    preferences.save(preference);
                    count[0]++;
                }
            }
        });
        return count[0];
    }

    /** Moves every title group other than the film's own to a new pending film, unfolding editions that straddle. */
    private void split(FileFact any, List<FileFact> filmFiles, Decision decision) {
        Film film = films.findById(any.filmId()).orElseThrow();
        unfoldStraddlers(filmFiles);
        List<String> moved = new ArrayList<>();
        Set<Long> movedEditions = new LinkedHashSet<>();
        for (TitleGroup group : decision.others()) {
            List<Long> fileIds = group.files().stream().map(FileFact::fileId).toList();
            Set<Long> editionIds = new LinkedHashSet<>();
            List<MediaFile> groupFiles = files.findAllById(fileIds);
            groupFiles.forEach(f -> editionIds.add(f.getEdition().getId()));
            Film created = new Film();
            created.setTitleAndSort(cut(group.title(), NAME_MAX));
            created.setYear(group.year());
            created.setAddedAt(groupFiles.stream().map(MediaFile::getAddedAt).min(Comparator.naturalOrder())
                .orElse(film.getAddedAt()));
            created.setMatchStatus(MatchStatus.PENDING);
            Film saved = films.saveAndFlush(created);
            for (Long editionId : editionIds) {
                editions.moveOne(editionId, saved);
            }
            movedEditions.addAll(editionIds);
            moved.add("\"" + group.title() + "\"");
        }
        forgetMoved(any.filmId(), movedEditions);
        log(GroupingChange.SPLIT, "Moved " + String.join(", ", moved) + " out of \"" + any.filmTitle()
            + "\" into films of their own");
    }

    /** An edition whose files belong to different title groups is unfolded into one edition per group first. */
    private void unfoldStraddlers(List<FileFact> filmFiles) {
        Map<Long, Map<String, List<FileFact>>> byEdition = new LinkedHashMap<>();
        for (FileFact fact : filmFiles) {
            byEdition.computeIfAbsent(fact.editionId(), k -> new LinkedHashMap<>())
                .computeIfAbsent(fact.parsed().comparisonTitle(), k -> new ArrayList<>()).add(fact);
        }
        byEdition.forEach((editionId, titles) -> {
            if (titles.size() > 1) {
                List<List<Long>> groups = titles.values().stream().map(list -> list.stream()
                    .sorted(Comparator.comparingInt(FileFact::partNumber).thenComparingLong(FileFact::fileId))
                    .map(FileFact::fileId).toList()).toList();
                join.unfold(editionId, groups);
            }
        });
    }

    /** The film's remembered choices must not point into editions that are gone from it. */
    private void forgetMoved(Long filmId, Set<Long> movedEditions) {
        FilmPreference preference = preferences.findById(filmId).orElse(null);
        if (preference == null) {
            return;
        }
        Set<Long> audio = new LinkedHashSet<>();
        Set<String> subtitles = new LinkedHashSet<>();
        for (Long editionId : movedEditions) {
            for (MediaFile file : editions.findById(editionId).orElseThrow().getMediaFiles()) {
                file.getAudioStreams().forEach(s -> audio.add(s.getId()));
                file.getSubtitleStreams().forEach(s -> subtitles.add(String.valueOf(s.getId())));
            }
        }
        if (movedEditions.contains(preference.getDefaultEditionId())) {
            preference.setDefaultEditionId(null);
        }
        if (movedEditions.contains(preference.getLastEditionId())) {
            preference.setLastEditionId(null);
        }
        if (audio.contains(preference.getAudioStreamId())) {
            preference.setAudioStreamId(null);
        }
        if (subtitles.contains(preference.getSubtitle())) {
            preference.setSubtitle("off");
        }
        preferences.save(preference);
    }

    /** Logs a flag unless the same one is already there; true when a line was written. */
    private boolean flag(String summary) {
        if (changes.existsByKindAndSummary(GroupingChange.FLAG, summary)) {
            return false;
        }
        log(GroupingChange.FLAG, summary);
        return true;
    }

    private void log(String kind, String summary) {
        Instant now = clock.instant();
        changes.save(new GroupingChange(now, kind, cut(summary, SUMMARY_MAX)));
    }

    private static String title(Collection<FileFact> facts, Long filmId) {
        Map<Long, String> titles = new LinkedHashMap<>();
        facts.forEach(f -> titles.put(f.filmId(), f.filmTitle()));
        return titles.get(filmId);
    }

    private static String cut(String text, int max) {
        return text.substring(0, Math.min(text.length(), max));
    }
}
