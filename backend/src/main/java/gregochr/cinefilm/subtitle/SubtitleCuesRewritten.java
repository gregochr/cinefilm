package gregochr.cinefilm.subtitle;

import java.util.Set;

/**
 * Published when the retime job rewrote or restored the stored cues of some downloads. The full-file probe and the
 * timing references keep an in-memory "done" set per sidecar, which must forget these ids so they are measured again
 * ({@code FullFitProbe.forget}, {@code TimingReferenceFetch.forget}).
 *
 * @param sidecarIds the downloads whose cues changed
 */
public record SubtitleCuesRewritten(Set<Long> sidecarIds) {
}
