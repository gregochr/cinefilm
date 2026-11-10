#!/bin/bash
# ============================================================================
# The commit gate of .github/workflows/dependabot-auto-merge.yml: decides whether
# a pull request's commits may be trusted for an automatic merge.
#
# Usage: gh api "repos/$GH_REPO/pulls/$PR/commits" --paginate | dependabot-commits-trusted.sh
#
# Reads the JSON that GET /repos/{owner}/{repo}/pulls/{n}/commits returns on
# stdin. Pages may arrive as one array after another (what `gh api --paginate`
# prints), as a single array, or as an array of arrays (`--slurp`): every shape
# is flattened to one list of commits. The script makes no network calls.
#
# Exit 0 (prints nothing) only when there is at least one commit and EVERY
# commit has author.login "dependabot[bot]" and commit.verification.verified
# true. Otherwise exit non-zero and print one line: "no commits", or the first
# offending commit (short sha, author, verified, reason). Empty, malformed or
# unexpected input fails closed: it can never print nothing and exit 0.
#
# Why commits and not the event's actor: the actor names whoever triggered the
# event, which a fork can arrange to be Dependabot; the author and signature of
# each commit cannot be arranged by someone else pushing to the branch.
# ============================================================================
set -euo pipefail

# The jq program is single-quoted on purpose: its \(...) and $variables are jq's, not the shell's.
# shellcheck disable=SC2016
FIRST_OFFENDER='
  flatten as $commits
  | if ($commits | length) == 0 then "no commits"
    else
      ([$commits[]
        | select((type != "object")
          or ((try .author.login catch null) != "dependabot[bot]")
          or ((try .commit.verification.verified catch null) != true))] | first) as $bad
      | if $bad == null then empty
        else "commit \((try $bad.sha catch null) // "unknown" | tostring | .[0:10])"
          + " (author \((try $bad.author.login catch null) // "none"),"
          + " signature verified: \((try $bad.commit.verification.verified catch null) | tostring),"
          + " reason: \((try $bad.commit.verification.reason catch null) // "none"))"
        end
    end'

if ! VERDICT=$(jq -s -r "$FIRST_OFFENDER"); then
    echo "unreadable commit list (not valid JSON)"
    exit 2
fi

if [[ -n "$VERDICT" ]]; then
    echo "$VERDICT"
    exit 1
fi
