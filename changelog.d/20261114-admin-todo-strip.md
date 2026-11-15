### Added — Admin To do status strip and rail counts

The Admin rail and phone tabs now show counts (To do, Subtitles, and "n live" on Playback), and the To do page opens with
"{n} things need you", the intro line and a status strip: Library (films and when it was last scanned), Playback (smooth
film sessions in the last 30 days) and Playing now. One shared read of `/api/admin/summary` feeds them all; it is read
again whenever a scan, import or subtitle job finishes or a change is applied, and every 30 s while the tab is visible.
