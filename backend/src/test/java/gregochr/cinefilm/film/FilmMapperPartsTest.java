package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.FilmDtos.EditionDto;
import gregochr.cinefilm.film.FilmDtos.QualityDto;
import gregochr.cinefilm.transcode.CutList;
import gregochr.cinefilm.transcode.EncoderMode;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** An edition split across several files as the API shows it: one edition, the logical streams, no part numbers. */
class FilmMapperPartsTest {

    private static final CutList DENSE = CutList.fromKeyframes(
        IntStream.range(0, 100).mapToObj(i -> i * 4000L).toList(), 400_000);
    private static final CutList SPARSE = CutList.fromKeyframes(List.of(), 400_000);

    private final Film film = film(1L, "The Two Towers", 2002, NOW);
    private final Edition edition = edition(film, 10L, "Extended Edition", 0, null);

    private MediaFile part(long id, int number) {
        MediaFile file = file(edition, "Towers pt" + number + ".m4v");
        file.setId(id);
        file.setPartNumber(number);
        file.setVideoCodec("H.264");
        file.setPixelFormat("yuv420p");
        file.setHeight(800);
        file.setWidth(1920);
        file.setDurationMs(6_000_000L);
        file.getAudioStreams().get(0).setId(id * 10);
        file.getAudioStreams().get(1).setId(id * 10 + 1);
        return file;
    }

    private EditionDto dto(EncoderMode mode, Function<MediaFile, CutList> cuts) {
        edition.recomputeRuntime();
        return FilmMapper.detail(view(film), null, false, mode, cuts).editions().get(0);
    }

    private static boolean available(EditionDto dto, String id) {
        return dto.qualities().stream().filter(q -> q.id().equals(id)).map(QualityDto::available).findFirst()
            .orElseThrow();
    }

    @Test
    void aSplitEditionIsOneEditionWithItsPartCountTheSummedRuntimeAndPartOnesStreams() {
        MediaFile first = part(1, 1);
        part(2, 2);

        EditionDto dto = dto(EncoderMode.VAAPI, f -> DENSE);

        assertThat(dto.parts()).isEqualTo(2);
        assertThat(dto.runtimeMs()).isEqualTo(12_000_000);
        assertThat(dto.file().id()).isEqualTo(first.getId());
        assertThat(dto.audioStreams()).extracting(FilmDtos.AudioStreamDto::id).containsExactly(10L, 11L);
        assertThat(dto.subtitleStreams()).hasSize(1);
    }

    @Test
    void aSplitFilmWhoseFilesHaveNoKnownBitrateStillListsItsQualities() {
        part(1, 1).setBitrate(null);
        part(2, 2);

        EditionDto dto = dto(EncoderMode.VAAPI, f -> DENSE);

        assertThat(dto.qualities()).extracting(QualityDto::bitrate).startsWith((Long) null, null);
        assertThat(available(dto, "original")).isTrue();
    }

    @Test
    void aFilmInOnePieceHasOnePart() {
        part(1, 1);

        assertThat(dto(EncoderMode.VAAPI, f -> DENSE).parts()).isEqualTo(1);
    }

    @Test
    void anEditionWithNoOriginalFileCountsAsOnePart() {
        MediaFile premade = part(1, 1);
        premade.setKind(MediaKind.PREMADE_720);

        assertThat(dto(EncoderMode.VAAPI, f -> null).parts()).isEqualTo(1);
    }

    @Test
    void theOriginalOfAMatchingSplitFilmIsAvailableBecauseItsCopyRungIsNotDirectPlay() {
        part(1, 1);
        part(2, 2);

        EditionDto dto = dto(EncoderMode.VAAPI, f -> DENSE);

        assertThat(dto.qualities()).extracting(QualityDto::id).containsExactly("auto", "original", "720p", "480p",
            "360p");
        assertThat(available(dto, "original")).isTrue();
        assertThat(available(dto, "auto")).isTrue();
    }

    @Test
    void theOriginalOfASplitFilmWhoseCopyIsRuledOutIsUnavailableSinceItCannotBeDirectPlayed() {
        part(1, 1);
        part(2, 2);

        EditionDto sparse = dto(EncoderMode.VAAPI, f -> SPARSE);

        assertThat(available(sparse, "original")).isFalse();
        assertThat(sparse.qualities()).extracting(QualityDto::id).contains("1080p");
    }

    @Test
    void aLongKeyframeGapInOnlyTheSecondPartRulesTheCopyOut() {
        MediaFile first = part(1, 1);
        part(2, 2);

        EditionDto dto = dto(EncoderMode.VAAPI, f -> f == first ? DENSE : SPARSE);

        assertThat(available(dto, "original")).isFalse();
    }

    @Test
    void aPartThatIsNotIndexedYetLeavesTheFormatToDecide() {
        MediaFile first = part(1, 1);
        part(2, 2);

        assertThat(available(dto(EncoderMode.VAAPI, f -> f == first ? DENSE : null), "original")).isTrue();
    }

    @Test
    void partsThatDoNotMatchOfferNoOriginalAndTranscodeTheTopRung() {
        part(1, 1);
        MediaFile second = part(2, 2);
        second.setWidth(1280);
        second.setHeight(536);

        EditionDto dto = dto(EncoderMode.VAAPI, f -> DENSE);

        assertThat(available(dto, "original")).isFalse();
        assertThat(dto.qualities()).extracting(QualityDto::id).contains("720p").doesNotContain("1080p");
    }

    @Test
    void withoutAnEncoderASplitFilmHasNoPlayableQualityAtAll() {
        part(1, 1);
        part(2, 2);

        EditionDto dto = dto(EncoderMode.UNAVAILABLE, f -> DENSE);

        assertThat(dto.qualities()).extracting(QualityDto::available).containsOnly(false);
    }

    @Test
    void aSingleFilmWithoutAnEncoderStillOffersTheOriginalForDirectPlay() {
        part(1, 1);

        EditionDto dto = dto(EncoderMode.UNAVAILABLE, f -> DENSE);

        assertThat(available(dto, "original")).isTrue();
        assertThat(available(dto, "auto")).isFalse();
    }

    @Test
    void aSplitFilmWithoutAKnownHeightOffersNoOriginal() {
        part(1, 1).setHeight(null);
        part(2, 2).setHeight(null);

        EditionDto dto = dto(EncoderMode.VAAPI, f -> DENSE);

        assertThat(dto.qualities()).extracting(QualityDto::id).containsExactly("auto", "original");
        assertThat(available(dto, "original")).isFalse();
    }
}
