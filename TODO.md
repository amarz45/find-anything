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

## Conversation history

- **Handle a latest exchange that is too long by itself.** `Conversation.trimToFit()` drops whole
  old exchanges and never the latest one, so one request that gathers a lot of tool output can
  exceed the model's context. The model request then fails and the agent falls back to instant
  search. Today only the step limit and the excerpt size bound it. A better version would shorten
  or drop the oldest tool results inside the latest exchange, keeping each call paired with a
  result.
- **Trim what is sent, not what is kept.** `trimToFit()` changes the conversation itself (as SD03
  draws it), so dropped exchanges also disappear from the saved conversation and from the GUI's
  view of it. A better version would keep the full history and trim only the copy that
  `PromptBuilder.buildRequest()` sends. This changes behaviour the report describes, so log it in
  `docs/logs/design-changes.md`.

## Citation verification

Amar may change these later; undecided.

- **Decide what an answer without citations reports.** `Answer.isVerified()` is true only when
  the answer has at least one citation and all of them are verified. That covers the design's
  case of citations that couldn't be parsed, but it also means a clarifying question, or "I found
  nothing about that", reports as not verified, and the GUI or `ask --json` would show it as
  unverified although nothing in it claims a source. A better version might tell "nothing to
  verify" apart from "failed to verify".
- **Consider forgiving typographic differences in quotes.** `CitationVerifier` matches a quote
  exactly apart from whitespace, as the report says ("close but not exact" stays unverified).
  Models often turn curly quotes, apostrophes and dashes into plain ones (and PDFs contain
  ligatures), so a true quote can come back unverified. Check how often this happens with the
  real models first. Folding those characters before comparing changes behaviour the report
  describes, so log it in `docs/logs/design-changes.md`.

## Extraction

- **Stop Tika appending PDF link annotations to page text.** PDFBox adds every link annotation's
  URL after a page's visible text. PDFs printed from a browser (Firefox + cairo) carry one
  annotation per visual fragment of a link, so the same URL repeats up to a dozen times per page,
  bloating chunks and skewing keyword scores toward link-heavy pages (seen on a job posting with
  79 annotations over 3 pages). Fix: set the PDF parser's `extractAnnotationText` to false in the
  extractor's Tika config (or drop duplicate URLs per page). Changes what F02 extracts, so log it
  in `docs/logs/design-changes.md`.
