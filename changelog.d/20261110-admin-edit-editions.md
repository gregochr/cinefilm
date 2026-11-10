### Added — Admin: Edit editions

Arrange a film's files into editions and parts, pick the default, or split off files that aren't this film. The panel opens from the Several files row in Admin and from Film detail, starts from the scanner's suggestion with the reason beside it, checks every rule as you go (the server checks the same rules and answers in the same words), and keeps each edition's resume position and watched state, including when it is renamed. Files marked Not this film become their own unmatched films, ready for Fix match. New: `GET` and `PUT /api/admin/films/{id}/editions`.
