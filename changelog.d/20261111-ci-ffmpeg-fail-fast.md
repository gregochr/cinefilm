### Fixed — CI: an apt mirror outage fails the ffmpeg install in minutes instead of hanging the backend job

On 2026-11-11 the GitHub runners could not resolve the Ubuntu apt mirrors, and the `Install ffmpeg` step hung until the job's 20-minute timeout cancelled it. The step now sets apt connection timeouts and retries, tries the install up to three times with a warning between attempts, and has a 6-minute ceiling, so a short blip recovers and a real outage fails quickly with a clear error annotation.
