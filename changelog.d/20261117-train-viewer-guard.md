### Added — The train viewer's guard

While the train viewer watches a film, everything the page tries to change on the server is answered inside Chrome and
never sent, except starting and ending the stream and the playback record. A progress save is answered in the shape the
server answers, any other change with an empty `204`. Nothing uses it yet: the run that watches a film comes in a
later change (`docs/engineering/train-viewer-plan.md`).
