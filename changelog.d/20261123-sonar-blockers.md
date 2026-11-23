### Fixed — SonarQube Cloud's quality gate on main

SonarQube Cloud's quality gate failed on every push to `main`. One finding caused it: the sheet tool's still source
opened a frame-reading job and closed it in a way the analyser could not follow. The job now goes to a named reading
that closes it. Twelve constants whose names differed from another member's only by case are renamed as well (for
example `LOOK` to `LOOK_FLAG` and `DatasetComplete.FORMAT` to `FORMAT_VERSION`), which clears the other open blocker
findings. Nothing behaves differently: the tools' options, the messages and the files they write are unchanged.
