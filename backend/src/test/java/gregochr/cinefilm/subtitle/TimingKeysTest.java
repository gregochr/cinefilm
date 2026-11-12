package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The names that say what a timing check was made of: they change with the file and the reference, not the offset. */
class TimingKeysTest {

    private static MediaFile part(Edition edition, long id, int number, long minutes) {
        MediaFile file = file(edition, "Towers - pt" + number + ".mkv");
        file.setId(id);
        file.setPartNumber(number);
        file.setDurationMs(minutes * MIN);
        file.setSizeBytes(1_000L * id);
        file.setModifiedAt(NOW.plusSeconds(id));
        return file;
    }

    private static Edition split() {
        Edition edition = edition(film(1L, "Towers", 2002, NOW), 40L, "Original", 120 * MIN, null);
        part(edition, 11, 1, 60);
        part(edition, 12, 2, 60);
        return edition;
    }

    private static SubtitleSidecar sidecar(Long fileId, boolean whole, String cues) {
        SubtitleSidecar sidecar = new SubtitleSidecar();
        sidecar.setId(5L);
        sidecar.setMediaFileId(fileId);
        sidecar.setWholeEdition(whole);
        sidecar.setCuesText(cues);
        return sidecar;
    }

    private static EmbeddedCueStarts read(MediaFile file) {
        EmbeddedCueStarts read = new EmbeddedCueStarts();
        read.setMediaFileId(file.getId());
        read.setFingerprint(TimingKeys.fingerprint(file));
        return read;
    }

    private static Map<Long, EmbeddedCueStarts> reads(MediaFile... files) {
        Map<Long, EmbeddedCueStarts> reads = new HashMap<>();
        for (MediaFile file : files) {
            reads.put(file.getId(), read(file));
        }
        return reads;
    }

    @Test
    void aFilesFingerprintIsItsSizeAndModificationTime() {
        MediaFile file = part(split(), 11, 1, 60);
        file.setSizeBytes(4_000_000_000L);
        file.setModifiedAt(Instant.ofEpochMilli(1_700_000_000_123L));

        assertThat(TimingKeys.fingerprint(file)).isEqualTo("4000000000:1700000000123");
    }

    @Test
    void aFileWithNoSizeOrNoTimeHasNoFingerprint() {
        MediaFile file = part(split(), 11, 1, 60);
        file.setSizeBytes(null);
        assertThat(TimingKeys.fingerprint(file)).isNull();

        file.setSizeBytes(5L);
        file.setModifiedAt(null);
        assertThat(TimingKeys.fingerprint(file)).isNull();
    }

    @Test
    void theIdentityNamesTheFileTheWholeFlagAndTheCues() {
        String identity = TimingKeys.identity(sidecar(11L, false, "1\n00:00:01,000 --> 00:00:02,000\nHi\n\n"));

        assertThat(identity).matches("11/0/[0-9a-f]{16}");
        assertThat(TimingKeys.identity(sidecar(11L, true, "x"))).startsWith("11/1/");
    }

    @Test
    void theIdentityChangesWithTheCuesTheFileAndTheWholeFlagButNotTheOffset() {
        SubtitleSidecar base = sidecar(11L, false, "cues A");
        String identity = TimingKeys.identity(base);

        assertThat(TimingKeys.identity(sidecar(11L, false, "cues B"))).isNotEqualTo(identity);
        assertThat(TimingKeys.identity(sidecar(12L, false, "cues A"))).isNotEqualTo(identity);
        assertThat(TimingKeys.identity(sidecar(11L, true, "cues A"))).isNotEqualTo(identity);
        base.setOffsetMs(900);
        base.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);
        assertThat(TimingKeys.identity(base)).isEqualTo(identity);
    }

    @Test
    void aPerPartFileIsFittedToItsOwnFileAndAWholeEditionFileToEveryPart() {
        Edition edition = split();

        assertThat(TimingKeys.referenceFiles(edition, sidecar(12L, false, "x"))).extracting(MediaFile::getId)
            .containsExactly(12L);
        assertThat(TimingKeys.referenceFiles(edition, sidecar(11L, true, "x"))).extracting(MediaFile::getId)
            .containsExactly(11L, 12L);
        assertThat(TimingKeys.referenceFiles(edition, sidecar(99L, false, "x"))).isEmpty();
    }

    @Test
    void aMissingPartMeansThereIsNothingToFitTo() {
        Edition edition = split();
        edition.originalParts().get(1).setPresence(Presence.MISSING);

        assertThat(TimingKeys.referenceFiles(edition, sidecar(11L, true, "x"))).isEmpty();
        assertThat(TimingKeys.referenceFiles(edition, sidecar(12L, false, "x"))).isEmpty();
        assertThat(TimingKeys.referenceFiles(edition, sidecar(11L, false, "x"))).extracting(MediaFile::getId)
            .containsExactly(11L);
    }

    @Test
    void aPerPartKeyIsTheFilesIdAndFingerprint() {
        Edition edition = split();
        MediaFile two = edition.originalParts().get(1);

        assertThat(TimingKeys.referenceKey(edition, sidecar(12L, false, "x"), reads(two)))
            .isEqualTo("12:12000:" + NOW.plusSeconds(12).toEpochMilli());
    }

    @Test
    void aWholeEditionKeyAlsoSaysWhereEachPartStarts() {
        Edition edition = split();
        MediaFile one = edition.originalParts().get(0);
        MediaFile two = edition.originalParts().get(1);

        assertThat(TimingKeys.referenceKey(edition, sidecar(11L, true, "x"), reads(one, two)))
            .isEqualTo("11:11000:" + NOW.plusSeconds(11).toEpochMilli() + "@0,12:12000:"
                + NOW.plusSeconds(12).toEpochMilli() + "@" + 60 * MIN);
    }

    @Test
    void theKeyChangesWhenAPartsFileChangesOrAPartsLengthDoes() {
        Edition edition = split();
        MediaFile one = edition.originalParts().get(0);
        MediaFile two = edition.originalParts().get(1);
        SubtitleSidecar whole = sidecar(11L, true, "x");
        String key = TimingKeys.referenceKey(edition, whole, reads(one, two));

        one.setDurationMs(61 * MIN);
        String longer = TimingKeys.referenceKey(edition, whole, reads(one, two));
        two.setSizeBytes(7L);
        String changed = TimingKeys.referenceKey(edition, whole, reads(one, two));

        assertThat(longer).isNotEqualTo(key);
        assertThat(changed).isNotEqualTo(longer);
    }

    @Test
    void thereIsNoKeyUntilEveryFileHasAReadOfItsCurrentVersion() {
        Edition edition = split();
        MediaFile one = edition.originalParts().get(0);
        MediaFile two = edition.originalParts().get(1);
        SubtitleSidecar whole = sidecar(11L, true, "x");

        assertThat(TimingKeys.referenceKey(edition, whole, reads(one))).isNull();
        Map<Long, EmbeddedCueStarts> stale = reads(one, two);
        two.setModifiedAt(NOW.plusSeconds(999));
        assertThat(TimingKeys.referenceKey(edition, whole, stale)).isNull();
        assertThat(TimingKeys.referenceKey(edition, whole, reads(one, two))).isNotNull();
    }

    @Test
    void thereIsNoKeyForAFileWithNoFingerprintOrAnUnknownPartLengthOrNoSuchFile() {
        Edition edition = split();
        MediaFile one = edition.originalParts().get(0);
        MediaFile two = edition.originalParts().get(1);
        Map<Long, EmbeddedCueStarts> reads = reads(one, two);

        assertThat(TimingKeys.referenceKey(edition, sidecar(99L, false, "x"), reads)).isNull();
        two.setDurationMs(null);
        assertThat(TimingKeys.referenceKey(edition, sidecar(11L, true, "x"), reads)).isNull();
        assertThat(TimingKeys.referenceKey(edition, sidecar(11L, false, "x"), reads)).isNotNull();
        one.setSizeBytes(null);
        assertThat(TimingKeys.referenceKey(edition, sidecar(11L, false, "x"), reads)).isNull();
    }

    @Test
    void theTimelineIsNullWhenAPartHasNoLength() {
        Edition edition = split();
        assertThat(TimingKeys.timelineOrNull(edition)).isNotNull();

        edition.originalParts().get(1).setDurationMs(null);

        assertThat(TimingKeys.timelineOrNull(edition)).isNull();
    }
}
