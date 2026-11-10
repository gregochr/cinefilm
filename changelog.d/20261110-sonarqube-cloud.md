### Added — SonarQube Cloud analysis in CI

A `SonarQube Cloud — Analysis` job in `ci.yml` analyses the Java backend, the React frontend, the shell scripts, the
Dockerfiles and the workflows on every PR and push to `main`, using the backend and frontend jobs' compiled classes and
coverage reports, and fails when the quality gate on new code fails. It is advisory (not a required check) and skips
cleanly when `SONAR_TOKEN` is unavailable (fork PRs, tag deploys).
