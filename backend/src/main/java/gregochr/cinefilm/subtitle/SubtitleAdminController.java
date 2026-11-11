package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.subtitle.SubtitleDtos.AttachRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.CuesDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.OffsetRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Admin: the forced subtitle files, where they belong and how they read. Every action takes a row id. */
@RestController
public class SubtitleAdminController {

    private final SubtitleAdminService service;

    /** Creates the controller over the Admin subtitle service. */
    public SubtitleAdminController(SubtitleAdminService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/subtitles")
    List<SidecarDto> list() {
        return service.list();
    }

    @GetMapping("/api/admin/subtitles/{id}/cues")
    CuesDto cues(@PathVariable Long id) {
        return service.cues(id);
    }

    @PostMapping("/api/admin/subtitles/{id}/attach")
    SidecarDto attach(@PathVariable Long id, @RequestBody AttachRequest request) {
        return service.attach(id, request);
    }

    @PostMapping("/api/admin/subtitles/{id}/detach")
    SidecarDto detach(@PathVariable Long id) {
        return service.detach(id);
    }

    @PutMapping("/api/admin/subtitles/{id}/offset")
    SidecarDto offset(@PathVariable Long id, @RequestBody OffsetRequest request) {
        return service.offset(id, request);
    }

    @PostMapping("/api/admin/subtitles/{id}/verify")
    SidecarDto verify(@PathVariable Long id) {
        return service.verify(id);
    }

    @DeleteMapping("/api/admin/subtitles/{id}/verify")
    SidecarDto unverify(@PathVariable Long id) {
        return service.unverify(id);
    }
}
