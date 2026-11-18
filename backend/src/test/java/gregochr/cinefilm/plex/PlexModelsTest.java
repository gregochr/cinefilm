package gregochr.cinefilm.plex;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.plex.PlexModels.PlexImage;
import org.junit.jupiter.api.Test;

class PlexModelsTest {

    @Test
    void imagesWithTheSameBytesAndTypeAreEqualAndShareAHashCode() {
        PlexImage one = new PlexImage(new byte[] {1, 2, 3}, "image/jpeg");
        PlexImage two = new PlexImage(new byte[] {1, 2, 3}, "image/jpeg");

        assertThat(one).isEqualTo(two);
        assertThat(one.hashCode()).isEqualTo(two.hashCode());
    }

    @Test
    void imagesDifferByBytesOrByType() {
        PlexImage image = new PlexImage(new byte[] {1, 2, 3}, "image/jpeg");

        assertThat(image).isNotEqualTo(new PlexImage(new byte[] {1, 2, 4}, "image/jpeg"));
        assertThat(image).isNotEqualTo(new PlexImage(new byte[] {1, 2, 3}, "image/png"));
        assertThat(image).isNotEqualTo(new PlexImage(null, "image/jpeg"));
        assertThat(image).isNotEqualTo(new PlexImage(new byte[] {1, 2, 3}, null));
        assertThat(image).isNotEqualTo("image/jpeg");
        assertThat(image.hashCode()).isNotEqualTo(new PlexImage(new byte[] {1, 2, 4}, "image/jpeg").hashCode());
        assertThat(image.hashCode()).isNotEqualTo(new PlexImage(new byte[] {1, 2, 3}, "image/png").hashCode());
    }

    @Test
    void imagesWithNoBytesOrNoTypeAreEqualToTheirTwins() {
        assertThat(new PlexImage(null, null)).isEqualTo(new PlexImage(null, null));
        assertThat(new PlexImage(null, null).hashCode()).isEqualTo(new PlexImage(null, null).hashCode());
    }

    @Test
    void anImagePrintsItsSizeNotItsBytes() {
        assertThat(new PlexImage(new byte[] {1, 2, 3}, "image/jpeg")).hasToString("PlexImage[3 bytes, image/jpeg]");
        assertThat(new PlexImage(null, "image/png")).hasToString("PlexImage[no bytes, image/png]");
    }
}
