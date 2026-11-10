### Changed — Library search matches whole-word prefixes, and the header search has its matching and ranking

Searching the Library now matches the start of words, not any run of letters: "w" no longer finds *Twisters*, "star wa"
finds *Star Wars*, and accents, apostrophes and punctuation are ignored ("amelie" finds *Amélie*, "ocon" finds
*Gavin O'Connor*). It looks at the title, the cast, the directors and the film's collection name. The header search
gets its engine too, ready for the dropdown in the next step: matches are ranked (a match on a title's first word
first, "The" ignored; films you are part-way through, then most recently played; people by how many of your films
they are in), grouped into Titles, Collections, Actors and Directors, and a query with no hit and at least four
letters falls back to a typo-tolerant match ("warripr" finds *Warrior*). Nothing is shown in the header yet.
