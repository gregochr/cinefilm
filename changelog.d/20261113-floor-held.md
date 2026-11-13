### Changed — Auto keeps 360p while the buffer is deep

The player drops quality as soon as the link slows, however much film it has banked. With the 240p floor and the
eight-minute buffer that meant falling to 240p at the start of every bad stretch and fetching the whole stretch at
240p, to be watched later. Now, once seven minutes are buffered, Auto does not go below 360p until fewer than four
are left: the buffer is spent first. It applies to films that have both qualities and never to a quality you chose.

The last of four steps (`docs/engineering/train-link-plan.md`). The two thresholds are estimates from one recorded
journey: a replay of it put the share of the film watched at 240p at about a third with this and over half without.
The next journey's record, with the buffer and the quality being fetched in each minute, says whether they are right.
