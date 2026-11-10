### Fixed — SonarQube Cloud blockers: null title guard and Dependabot auto-merge trust

A title guess without a title is now refused with a clear message where a film is created, instead of failing deep in
the sort-title code (it cannot happen with real files; the guard makes that explicit). The Dependabot auto-merge
workflow no longer trusts the event's actor: the label job checks the PR's author and origin, and the merge job
requires every commit on the PR to be authored by Dependabot with a verified signature.

The commit check lives in `scripts/dependabot-commits-trusted.sh`, with fixture tests in `scripts/test/`; the merge job runs it from a checkout of the default branch, never the PR head.
