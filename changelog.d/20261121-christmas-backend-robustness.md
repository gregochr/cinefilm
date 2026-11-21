### Fixed — Christmas films: safer choices and honest keyword progress

A Christmas choice request with no `choice` field (or a misspelt one) is now refused instead of silently forgetting your override, and a blank film id in the states list is refused too. The Christmas screen's keyword progress now says when the backfill has stalled (TMDB off, or the last run could not finish). Behind the scenes, Home, the Christmas screen and each film's state share one Christmas rule, Home reads your choices with one query, and "needs a match" is one rule in code and in the To do queries.
