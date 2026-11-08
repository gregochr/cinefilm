package gregochr.cinefilm.library;

import gregochr.cinefilm.library.AdminDtos.Attention;
import gregochr.cinefilm.library.AdminDtos.ScanStatusDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin: the library scan and the films needing attention. */
@RestController
public class AdminController {

    private final AdminService admin;
    private final ScanService scans;

    /** Creates the controller. */
    public AdminController(AdminService admin, ScanService scans) {
        this.admin = admin;
        this.scans = scans;
    }

    @GetMapping("/api/admin/scan")
    ScanStatusDto scan() {
        return admin.status();
    }

    @PostMapping("/api/admin/scan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ScanStatusDto startScan() {
        scans.start("admin");
        return admin.status();
    }

    @GetMapping("/api/admin/attention")
    Attention attention() {
        return admin.attention();
    }
}
