### Changed — CI and the image install packages without running their scripts

`npm ci` in CI, in the weekly Stryker run and in the Docker image now carries `--ignore-scripts`, and CI's `pip install`
of diff-cover carries `--only-binary :all:`, so a dependency cannot run code of its own while it is being installed.
Nothing the build needs changes: the only package with an install script is `fsevents`, which is for macOS and is not
installed there. This clears five of SonarQube Cloud's older findings (`S6505` three times, `S8541` twice).
