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

### 2026-10-26: corpus test timed out on the Windows CI runner
- **Found by:** CI on the Slice 1 pull request (`build (windows-latest)`):
  `FindAnythingServiceTest.indexesTheCorpusAndAnswersSearchesAndPreviews()` gave up after 60 s,
  while the same test takes 3.5 s on Linux. The uploaded report could not say what the worker was
  doing, so the first commit only added timestamps, the index status in the timeout message and
  report uploads on every run.
- **Symptom:** one file, `edge/scanned-receipt.pdf`, took 60 s to extract (82 s for the handbook
  PDF on the next run); every other file took milliseconds.
- **Cause:** not the file: it was the first PDF with a non-embedded font that any Tika child
  parsed on that machine. PDFBox then scans every installed font once to choose a substitute and
  writes `.pdfbox.cache` in the user's home folder. A cold scan with Windows Defender takes a
  minute on the runner. Latent product risk: with the cache in the home folder, an unwritable
  home would repeat the scan every time the child restarts after its one-minute idle exit.
- **Fix:** the test waits up to 180 s with a status-bearing message
  ([4db5034](https://github.com/amarz45/find-anything/commit/4db50340110ffd349bc29bdd0183b411d1c96516)),
  and `IsolatedTextExtractor` passes `-Dpdfbox.fontcache` so the cache lives in the app's Tika
  folder ([637839d](https://github.com/amarz45/find-anything/commit/637839dcab5862074db335594af0cd6de3ebbd49)).
  Recorded in `docs/notes/tika/findings.md`.
