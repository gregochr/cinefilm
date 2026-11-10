package gregochr.cinefilm.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * THROWAWAY PROBE: deliberately vulnerable. Never merge.
 *
 * <p>Reads any file named by the request, to prove the CodeQL ruleset blocks new high-severity alerts.
 */
@RestController
public class CodeqlGateProbeController {

  /**
   * Returns the bytes of the file at the given path, with no validation.
   *
   * @param path the file path taken straight from the request
   * @return the file bytes
   * @throws IOException if the file cannot be read
   */
  @GetMapping("/api/probe/file")
  public byte[] file(@RequestParam String path) throws IOException {
    return Files.readAllBytes(Path.of(path));
  }
}
