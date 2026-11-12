### Fixed

Cleared three SonarQube reliability findings that failed the quality gate on `main`: the forced-download refresher's "a busy library deferred this run" flag is now an `AtomicBoolean` instead of a `volatile` field updated with a compound assignment, and the timing fit's internal score record (which holds an array) now compares, hashes and prints by array contents. No behaviour changes.
