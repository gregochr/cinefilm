### Security — Dev tooling no longer pulls in a vulnerable `qs`

The frontend's dev-only mutation-testing tool (Stryker) reaches `qs` through `typed-rest-client`, which pins it exactly
at 6.15.1, a version with four published advisories, all rated moderate (so none failed the high-level audit). They are
cleared ahead of time: `frontend/package.json` now overrides `qs` to 6.16.1, the first release that fixes all four, for
that one package only. Nothing in the shipped app uses it.

### Security — A dated allowance for one unpatched advisory

`scripts/npm-audit.sh` now has a short allow list, each entry with an expiry date and a reason. The one entry is
GHSA-fc5q-39xc-vjpf (picomatch ReDoS, no patched release yet, reached only through dev tooling), allowed until
2026-12-31. This allowance is what turns the frontend audit green. A finding passes only if every advisory behind it is allowed and unexpired; every other advisory, and this
one after its date, fails as before. The CI log names each ignored advisory and its expiry.
