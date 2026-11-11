### Changed — The Library list is lighter and browse answers can be a 304

`GET /api/films` no longer carries `backdropPath` on each film (the Library never shows it): on a synthetic 1,087-film
library the list went from 467,156 to 363,891 bytes, and from 115,937 to 70,170 bytes gzipped. The browse answers
(Home, the Library list, Film detail, Person, Collections, a Collection) now carry a weak ETag and
`Cache-Control: no-cache, private`, like the search index (which gains `private`), so the browser keeps a copy and asks
before every use; an unchanged answer is a 304 with no body (a few hundred bytes of headers instead of the whole list),
and a change is never hidden behind one. Answers under 2 KB are no longer compressed (too small to matter); errors are
unchanged.
