### Docs — a plan for a film bank held by the player, outside the browser's buffers

Measured in the home replay rig, Chrome's audio buffer holds between about 10 and 16 minutes of film, short of the
19 minutes the longest bad stretch on the train needs. `docs/engineering/outside-bank-plan.md` plans a bank the
player keeps in its own memory: before a known stretch it fetches the film ahead, one segment at a time and in
order (the server allows one fetcher per session), and hls.js plays it from there. It replaces the engine part of
the train look-ahead plan.
