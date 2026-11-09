package gregochr.cinefilm.transcode;

import gregochr.cinefilm.transcode.SessionDtos.IndexDto;
import gregochr.cinefilm.transcode.SessionDtos.TranscodingStatus;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin's Transcoding panel: the encoder, the keyframe index, the live sessions, and Stop. */
@RestController
public class TranscodingAdminController {

    private final Transcoder transcoder;
    private final IndexJob indexJob;
    private final SessionManager manager;
    private final SessionService sessions;

    /** Creates the controller. */
    public TranscodingAdminController(Transcoder transcoder, IndexJob indexJob, SessionManager manager,
                                      SessionService sessions) {
        this.transcoder = transcoder;
        this.indexJob = indexJob;
        this.manager = manager;
        this.sessions = sessions;
    }

    @GetMapping("/api/admin/transcoding")
    TranscodingStatus status() {
        IndexJob.Progress progress = indexJob.progress();
        return new TranscodingStatus(transcoder.mode().name().toLowerCase(Locale.ROOT),
            new IndexDto(progress.indexed(), progress.total()), manager.hlsBytes(), manager.sessions());
    }

    @DeleteMapping("/api/admin/transcoding/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void stop(@PathVariable String id) {
        sessions.end(id);
    }
}
