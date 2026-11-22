### Added — credits AUTO markers: the trigger

Storing a version 2 `FOUND` detection (or a result that replaces one) now asks the credits apply pass to run, once
the library lock is released, and so does a Fix match, so a re-matched film's deny rules apply at once. Nothing
changes until the `AUTO` switch is turned on.

A save that finds the library lock held by an apply pass now waits (up to a minute) instead of losing the result.
