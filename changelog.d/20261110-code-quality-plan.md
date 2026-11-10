### Docs — code quality gates and feedback loop plan

`docs/engineering/code-quality-plan.md` records the 2026-11-10 baseline (PMD, CPD, complexity, layering, knip, an ESLint maintainability probe, duplication, bundle size and the first CodeQL scan: 20 alerts, 5 high) and a five-phase plan: clear and gate CodeQL, add knip, add ArchUnit rules, add SonarCloud on new code, then a weekly drift routine. It also records that the repo is now public, `main` is protected with three required checks, and CodeQL default setup is on.
