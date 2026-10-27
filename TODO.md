# To do

Low-priority improvements noticed during implementation. None of these is in the Stage 1 design;
ask before doing one and log it in `docs/logs/design-changes.md` if it changes behaviour the
report describes.

## CLI search output

- **Say when a hit is on the file name only.** Every chunk carries its file's name as a searchable
  field, so a file found by name returns its first chunk as the "best" one and `findany search`
  prints the start of the file as if it were the matching passage (for `.typ` files that's the
  template boilerplate). `SearchIndex.keywordSearch()` would need to report which field matched,
  and the CLI would then print "matched by name" instead of a snippet.
- **Centre the snippet on the first matching word** instead of the chunk's start, reusing the
  match finding that `show --highlight` already does. Today a single mention deep in a long chunk
  (a job posting that says "submit your résumé") is cut off by the 160-character limit, so the
  user can't see why the file matched.
- **`show` option to print only the matches' surroundings.** `show --highlight` prints the whole
  file (the F11 preview is the whole text with highlight ranges, which is what the GUI pane
  needs), so one match on page 3 of a long PDF means scrolling past pages 1 and 2. A
  `--context <lines>` or `--matches-only` option would print just the lines around each match.

## Extraction

- **Stop Tika appending PDF link annotations to page text.** PDFBox adds every link annotation's
  URL after a page's visible text. PDFs printed from a browser (Firefox + cairo) carry one
  annotation per visual fragment of a link, so the same URL repeats up to a dozen times per page,
  bloating chunks and skewing keyword scores toward link-heavy pages (seen on a job posting with
  79 annotations over 3 pages). Fix: set the PDF parser's `extractAnnotationText` to false in the
  extractor's Tika config (or drop duplicate URLs per page). Changes what F02 extracts, so log it
  in `docs/logs/design-changes.md`.
