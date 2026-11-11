package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryRoot;
import gregochr.cinefilm.library.LibraryScanner.Found;
import gregochr.cinefilm.library.LibraryScanner.FoundSidecar;
import gregochr.cinefilm.subtitle.SidecarMatcher.FileRef;
import gregochr.cinefilm.subtitle.SidecarMatcher.Match;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the {@code subtitle_sidecar} table in step with the forced subtitle files a scan found. Every scan, for every
 * file: a new or changed file is read and parsed (an unchanged one is not read again), a file that is not found is
 * marked missing (never deleted, so a brief outage of the media share cannot lose the owner's work, and not at all
 * under a folder the walk could not read), and a file that comes back is present again. Then every file the owner has
 * not attached by hand is matched to a film file by name, whether or not that film changed. The media mount is only
 * read, and only through {@link LibraryRoot}.
 */
@Service
public class SidecarSync {

    private static final Logger LOG = LoggerFactory.getLogger(SidecarSync.class);

    private final SubtitleSidecarRepository sidecars;
    private final MediaFileRepository files;
    private final LibraryRoot root;
    private final Clock clock;

    /** Creates the service. */
    public SidecarSync(SubtitleSidecarRepository sidecars, MediaFileRepository files, LibraryRoot root,
                       Clock clock) {
        this.sidecars = sidecars;
        this.files = files;
        this.root = root;
        this.clock = clock;
    }

    /**
     * Brings the table up to date with a scan's walk, then matches every sidecar the owner has not attached.
     *
     * @param found what the walk found, including the folders it could not read
     */
    @Transactional
    public void sync(Found found) {
        Instant now = clock.instant();
        Map<String, SubtitleSidecar> existing = new HashMap<>();
        sidecars.findAll().forEach(s -> existing.put(s.getPath(), s));
        Set<String> seen = new HashSet<>();
        for (FoundSidecar file : found.sidecars()) {
            seen.add(file.path());
            update(existing.get(file.path()), file, now);
        }
        for (SubtitleSidecar row : existing.values()) {
            if (row.getPresence() == Presence.PRESENT && !seen.contains(row.getPath())
                && !found.isUnder(row.getPath())) {
                row.setPresence(Presence.MISSING);
                row.setUpdatedAt(now);
            }
        }
        sidecars.flush();
        SidecarMatcher matcher = matcher();
        for (SubtitleSidecar row : sidecars.findAll()) {
            if (!row.isOwnerAttached()) {
                apply(row, matcher.match(row.getPath()).orElse(null), now);
            }
        }
    }

    /**
     * Matches one sidecar by name again (the owner took its manual attachment away).
     *
     * @param row a sidecar that is not owner-attached
     */
    @Transactional
    public void rematch(SubtitleSidecar row) {
        apply(row, matcher().match(row.getPath()).orElse(null), clock.instant());
    }

    private SidecarMatcher matcher() {
        List<FileRef> refs = files.findKnownOriginals().stream().map(k -> new FileRef(k.id(), k.path())).toList();
        return new SidecarMatcher(refs);
    }

    private void apply(SubtitleSidecar row, Match match, Instant now) {
        Long fileId = match == null ? null : match.mediaFileId();
        boolean whole = match != null && match.whole();
        if (!Objects.equals(row.getMediaFileId(), fileId) || row.isWholeEdition() != whole) {
            row.setMediaFileId(fileId);
            row.setWholeEdition(whole);
            row.setUpdatedAt(now);
        }
    }

    private void update(SubtitleSidecar row, FoundSidecar file, Instant now) {
        if (row != null && row.getSizeBytes() == file.sizeBytes()
            && row.getModifiedAt().toEpochMilli() == file.modifiedAt().toEpochMilli()) {
            if (row.getPresence() == Presence.MISSING) {
                row.setPresence(Presence.PRESENT);
                row.setUpdatedAt(now);
            }
            return;
        }
        Optional<byte[]> bytes = read(file.path());
        if (bytes.isEmpty()) {
            return;
        }
        SubtitleSidecar target = row == null ? new SubtitleSidecar() : row;
        SrtParser.Parsed parsed = SrtParser.parse(bytes.get());
        target.setPath(file.path());
        target.setSizeBytes(file.sizeBytes());
        target.setModifiedAt(file.modifiedAt());
        target.setPresence(Presence.PRESENT);
        target.setCuesText(SrtParser.format(parsed.cues()));
        target.setParseReport(parsed.report().describe());
        target.setCueCount(parsed.cues().size());
        target.setLastEndMs(parsed.cues().stream().mapToLong(SubtitleCue::endMs).max().orElse(0));
        target.clearVerification();
        target.setUpdatedAt(now);
        sidecars.save(target);
    }

    /** The file's bytes, at most one more than the parser accepts, or empty when it cannot be read. */
    private Optional<byte[]> read(String path) {
        Optional<Path> real = root.resolveFile(path);
        if (real.isEmpty()) {
            LOG.warn("Subtitle file {} is no longer available", path);
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(real.get())) {
            return Optional.of(in.readNBytes(SrtParser.MAX_BYTES + 1));
        } catch (IOException e) {
            LOG.warn("Could not read subtitle file {}: {}", path, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
