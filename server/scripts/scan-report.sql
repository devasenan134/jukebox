-- Jukebox scan report: run with sqlite3 against data/jukebox.db (read-only).
.mode list
.separator ' : '
SELECT 'albums', count(*) FROM albums;
SELECT 'releases ' || kind, count(*) FROM releases GROUP BY kind;
SELECT 'tracks', count(*) FROM tracks;
SELECT 'recordings (kept)', count(*) FROM recordings WHERE merged_into IS NULL;
SELECT 'recordings merged into another', count(*) FROM recordings WHERE merged_into IS NOT NULL;
SELECT 'recordings by version ' || version, count(*) FROM recordings WHERE merged_into IS NULL GROUP BY version ORDER BY 2 DESC;
SELECT 'recordings with a JioSaavn id', count(*) FROM recordings WHERE saavn_id IS NOT NULL AND merged_into IS NULL;
SELECT 'songs (version groups)', count(*) FROM songs;
SELECT 'files', count(*) FROM files WHERE missing_since IS NULL;
SELECT 'files missing', count(*) FROM files WHERE missing_since IS NOT NULL;
SELECT 'files hashed', count(*) FROM files WHERE audio_md5 IS NOT NULL;
SELECT 'recordings fingerprinted', count(*) FROM recordings WHERE length(fingerprint) > 0;
SELECT 'people', count(*) FROM people;
SELECT 'people as ' || role, count(DISTINCT person_id) FROM recording_credits GROUP BY role;
SELECT 'album composers', count(DISTINCT person_id) FROM album_credits;
SELECT 'recordings with lyrics', count(DISTINCT recording_id) FROM lyrics;
SELECT 'lyrics synced', count(*) FROM lyrics WHERE synced = 1;
SELECT 'lyrics plain ' || script, count(*) FROM lyrics WHERE synced = 0 GROUP BY script;
SELECT 'albums with a cover', count(*) FROM albums WHERE cover_id IS NOT NULL;
SELECT 'artwork images', count(*) FROM artwork;
SELECT 'albums without a year', count(*) FROM albums WHERE year IS NULL;
SELECT 'albums with 2+ soundtrack releases', count(*) FROM (SELECT album_id FROM releases WHERE kind = 'soundtrack' GROUP BY album_id HAVING count(*) > 1);
SELECT 'scans', count(*), max(info) FROM scans;
