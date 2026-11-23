# Train journeys

Written 2026-11-23. How a train journey's log gets from the laptop into the database, how the train it was on is
added to it, and how to take either away again. The design and its reasons are in `journey-data-plan.md`; the
logger has its own manual, `train-feed-logger.md`; the routes are in `foundation-plan.md` under "Journey data API".

## What it is for

The train's Wi-Fi rides on mobile signal, so where the train is decides what the link delivers. The logger records
what the train's own Wi-Fi system says about that, every thirty seconds: the position, each modem's signal and
cell, how many passengers are online, the speed limit the laptop is given, and the laptop's own state. The
playback records say how the film fared in the same minutes. Kept side by side in the database, the two can be
laid over each other. Nothing is laid over anything yet: that view comes later (`journey-data-plan.md`, item 8 of
its pull requests).

## From the train to the database

1. **On the train:** run the logger (`train-feed-logger.md`, "Running it"). It writes one file,
   `~/train-feed-logger/logs/train-feed-<date>-<time>.jsonl`. Stop it when getting off.
2. **Afterwards, in the app:** Admin, Playback, the "Train journeys" card, "Open journeys…"
   (`/admin/playback/journeys`). Choose the file, read what will be sent, press Import.
3. **Optionally:** give the journey its train's file, so that the database knows which service it was and when it
   was planned to be, and was, at each timing point.

A log is imported once, after the logger has stopped. The page reads the file at the moment it is chosen: if the
file changes after that, reload the page and choose it again.

## Importing a log

The file is read in the browser. Nothing is sent until Import is pressed, and only what the page lists is sent.

**What will be sent** is shown first:

| Row | What it says |
|---|---|
| File name | The file's name, which is stored with the journey; "Not sent" when it is longer than 200 characters or has a character outside printable ASCII (it then counts under "Left out", as `sourceFile`) |
| Rounds | How many thirty-second rounds the log holds (at most 2,000 in one import) |
| Logged | The first and the last round's time, by Britain's clock |
| Train units | The units the feed named; a new journey begins each time the unit named changes |
| Modems | How many of the train's modems the feed listed |
| Logger version | 2 or 3 |
| Laptop | Whether the log names the laptop's model, chip and memory (version 3 does) |
| Class | Standard, First, or "Not answered" |
| Left out | Values the log held that were out of range or of the wrong kind, by field, with a count. They are not sent |
| Lines not read | Lines of the file that were not JSON, when there are any |

"Left out" should say "Nothing". If it names a field with a large count, do not import: the page's idea of that
field does not fit what the train sends, and that is a bug to fix first. It happened once: the feed writes a
modem's cell id as hexadecimal text (`0001E240`), and the page first took it for a decimal number. The database
keeps the id's value as a whole number (123456 for that one); `to_hex(cell_id)` in SQL gives the feed's digits
back.

**What never leaves the laptop:** the laptop's addresses, the feed's own addresses, the Wi-Fi network's name, and
everything else the logger writes that the request has no name for (how long each of the feed's answers took, its
response headers, the speed sample). The server refuses a request that carries a name it does not know.

**If the page will not take the file,** it says why in one sentence: no logger lines, no start line (not a train
feed log), a logger version it does not know, no rounds, rounds out of order, more than 2,000 rounds, or too big
to send (4,000,000 characters as a request).

**If the server refuses it:**

| The page says | What happened |
|---|---|
| "The server refused this log. Nothing was stored." | A value the server does not accept got through the page, or one of the train's modems changed its type partway through a journey, which the server will not store. Either is a bug to report: the page is meant to catch what the server refuses |
| "This log is already imported." | A journey of this logger run is stored. Delete its journeys to import it again |
| "Another import was running. Try again." | Two imports met. The second try gets the true answer |
| "Couldn’t import the log. Try again." | The request did not get through, or the server failed |

After an import the page says how many journeys were made, how many rounds they hold and how many viewings were
linked: the playback records that overlap the journey's time, two minutes allowed either side, and were not
watched at home. A record made after the import is not linked; delete the journey and import again to link it.

## The list

Every stored journey, the newest logger run first. A row says:

- when it was logged;
- the train unit, which part of the log it is when a log became more than one journey, the class, the rounds
  and the viewings linked;
- its train ("Train 9Z99 on 4 Mar 2030") or "No train yet";
- the file's name and the laptop's model, when known.

## The train's file

The feed names the train unit, not the service. Which service it was, and its planned and actual times, are in
the railway's open running data, which sites such as OpenTrainTimes show for past trains. The server fetches
nothing: the times are copied by hand into a small JSON file, once for a train, and given to the journey on the
page.

    {
      "uid": "X12345",
      "date": "2030-03-04",
      "headcode": "9Z99",
      "operator": "Example Rail",
      "source": "https://example.invalid/run",
      "calls": [
        { "location": "Alpha Junction", "plannedDeparture": "22:00", "actualDeparture": "22:01:30" },
        { "location": "Gamma Loop", "plannedPass": "22:50:30", "actualPass": "22:53" },
        { "location": "Theta Central", "plannedArrival": "00:45", "actualArrival": "00:47:30" }
      ]
    }

- **`uid`, `date`, `headcode`** are required: the timetable's id for the service and its headcode (ten characters
  at most each), and its running date (`YYYY-MM-DD`, from 2020 to 2100: the day the train left its origin, by
  Britain's clock). `operator` and `source` (where the times were read) are optional. Every text is printable
  ASCII and not empty.
- **Nothing else.** Only the names shown here are allowed, in the file and in a call, and apart from the list of
  calls every value is text in quotes. A file with any other name in it (a platform, a note), or a number where
  text belongs, is refused whole.
- **`calls`** are the train's timing points in order, from its origin to its destination, two at least and 300
  at most. Give the whole run, not only the stretch travelled: a later file for the same train replaces what is
  stored only when it begins and ends at the same places.
- **A call** has its `location` (100 characters at most) and up to six clock times, as the railway shows them:
  `plannedArrival`, `plannedDeparture`, `plannedPass`, `actualArrival`, `actualDeparture`, `actualPass`. Write
  `HH:MM`, or `HH:MM:SS` with the seconds 00 or 30 (the railway times to the half minute; where a site shows a
  half as ½, write `:30`). A call has at least one planned time. A train either passes a point or stops at it, so a planned pass is never
  given with a planned arrival or departure, nor an actual pass with an actual arrival or departure; the actual
  times may be of the other kind (a train planned to pass that stopped), or missing.
- **Write clock times only.** The server works out the dates: the first planned time is on the running date,
  and a planned time that is earlier on the clock than the one before it by more than twelve hours is the next
  day. An actual time goes on the day that brings it nearest its planned time. Nobody has to get the time zone,
  summer time or midnight right by hand.
- The file is 200,000 characters at most.

On a journey's row, "Add train file…" (or "Replace train file…") opens a panel. Choose the file; the page shows
its timetable id, running date, headcode, the number of timing points and the first and last place; "Add train"
(or "Replace train") sends it. The whole file is sent.

| The page says | What happened |
|---|---|
| "That file isn’t JSON." or "That file isn’t a train file. …" | The page could not read it as a train's file |
| "The server refused this train file. Nothing was stored." | A rule above is broken: a name the file may not have, a value that is not text, a time not in its form, a pass with an arrival, planned times that go back, an arrival after its departure |
| "That train did not run during this journey. Nothing was stored." | The file's times, half an hour allowed either side, do not overlap the journey's rounds (the wrong day, or the wrong train), or the journey has no rounds |
| "This train is already stored with other end points. Nothing was changed." | A file for this train is stored and this one begins or ends somewhere else. Give the whole run |
| "Another request was running. Try again." | Two requests met |
| "Couldn’t store the train. Try again." | The request did not get through, or the server failed |

Two journeys on one train share the one stored train. A train that no journey has any more is removed from the
database by itself.

## Taking things away

- **"Delete…"** on a row deletes the journey with its rounds, after a confirmation. The log on the laptop is not
  touched. Once every journey of a log is deleted the log can be imported again.
- **"Replace train file…"** corrects a train's times.
- **"Remove train…"**, on a row that has a train, takes the train away from that journey, after a confirmation.
  The journey and its rounds stay.

A delete or a removal that cannot be done says so in its confirmation, which stays open: "Another request was
running. Try again.", or "Couldn’t delete the journey. Try again." and "Couldn’t remove the train. Try again." when
the request did not get through or the server failed. A journey that was already deleted somewhere else is told
in a toast, "That journey was already deleted", and the list is read again.

## Where it is kept, and who can see it

The journeys are in the database (`journey`, `journey_sample`, `journey_modem`, `journey_modem_sample`,
`journey_playback`, `device`, `train_run`, `train_run_call`), in its dumps, and in Admin's answers behind the
sign-in, and nowhere else (`SECURITY.md`, "Journey data"). A journey's positions and its train are the owner's
travel. The logs themselves stay in `~/train-feed-logger/logs` on the laptop and are never committed: the
repository is public, and every example in it, this page's included, is made up.

## What nobody has verified

- **A real log imported on the real server.** The page's reading of a real log was checked on the laptop (every
  round read, nothing left out, once the cell id was read as hexadecimal), and the request it makes was accepted
  and stored by the server's import code on the in-memory test database. It has not been imported on PostgreSQL,
  and its viewings have not been linked to real playback records.
- **A real train's file.** Only the made-up example has been through the server.
- **The page against the real server in a browser.** It was looked at in a browser against a stand-in that
  answers as the contract says; the real server's refusals and a log with two train units were not seen.
