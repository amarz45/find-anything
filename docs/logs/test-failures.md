# Defects found and fixed

Source for "important defects found and corrected" (Stage 2 report, section 6) and for Stage 3's
"discussion of important failures found and fixed". Add an entry whenever a test, a manual check,
CI, or a demo recording catches a real bug.

Template:

```
### YYYY-MM-DD: short title
- **Found by:** which test, check, or observation
- **Symptom:** what went wrong
- **Cause:**
- **Fix:** (commit link)
```

## Entries

### 2026-10-25: every rescan re-indexed every unchanged file
- **Found by:** the manual end-of-slice check of the `findany` CLI (each command showed
  "Indexing... 22 pending" although nothing had changed). The unit tests only checked that
  changes *were* picked up, not that unchanged files were left alone.
- **Symptom:** `Indexer.rescanFolder()` queued every file on every run, so each CLI command
  re-extracted the whole corpus (about 4 seconds) and the failure warnings repeated every time.
- **Cause:** the rescan compared the file system's modification time, which has nanosecond
  precision, with the stored one, which `SearchIndex` keeps in milliseconds. They were never equal.
- **Fix:** `Indexer` truncates modification times to milliseconds both when storing and when
  comparing, and `IndexerTest.aRescanWithNoChangesIndexesNothingAgain()` guards it.

### 2026-10-25: `index rebuild` emptied the index instead of rebuilding it
- **Found by:** `IndexerTest.rebuildDropsStaleEntriesAndIndexesEverythingAgain()` and
  `CliAppTest`, right after the modification-time fix above (which had masked it).
- **Symptom:** after a rebuild the index held 0 files.
- **Cause:** `Indexer.rebuild()` set its flag and queued the folders, but the worker checked the
  flag only at the top of its loop. When it was already waiting on the queue, it took a folder and
  rescanned it against the still-full index first (nothing had changed, so nothing was queued),
  and only then dropped every entry.
- **Fix:** the worker checks the rebuild flag right after taking a path, so the drop always
  happens before the rescan it triggers.
