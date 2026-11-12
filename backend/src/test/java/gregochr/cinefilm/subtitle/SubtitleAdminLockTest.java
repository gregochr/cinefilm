package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.subtitle.SubtitleDtos.OffsetRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** The library lock is held for the whole transaction of an Admin change and given back only after the commit. */
class SubtitleAdminLockTest {

    @Test
    void theLockIsStillHeldWhenTheTransactionCommitsAndFreeOnlyAfterwards() {
        LibraryLock lock = new LibraryLock();
        List<String> events = new ArrayList<>();
        PlatformTransactionManager manager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                events.add("begin, lock " + lock.holder());
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                events.add("commit, lock " + lock.holder());
            }

            @Override
            public void rollback(TransactionStatus status) {
                events.add("rollback, lock " + lock.holder());
            }
        };
        SubtitleSidecarRepository sidecars = mock(SubtitleSidecarRepository.class);
        SubtitleSidecar row = new SubtitleSidecar();
        row.setId(5L);
        row.setPath("A.en.forced.srt");
        when(sidecars.findById(5L)).thenReturn(Optional.of(row));
        when(sidecars.findAllByOrderByPathAscIdAsc()).thenReturn(List.of(row));
        SubtitleAdminService service = new SubtitleAdminService(sidecars, mock(MediaFileRepository.class),
            mock(EditionRepository.class), mock(SidecarSync.class), mock(ForcedResolver.class),
            mock(ForcedDownloadBlockRepository.class), CLOCK, lock, manager, mock(ApplicationEventPublisher.class));

        service.offset(5L, new OffsetRequest(250));

        assertThat(events).containsExactly("begin, lock subtitle", "commit, lock subtitle");
        assertThat(lock.holder()).isNull();
        assertThat(row.getOffsetMs()).isEqualTo(250);
    }
}
