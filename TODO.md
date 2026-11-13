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
  result. Seen on 2026-11-08: one question about taxes reached about 79,000 characters by its
  fifth model call. A cloud model's context takes that, at a cost, since the growing history is
  sent again on every call. A local model at 16,384 tokens would have failed at the fourth call.
  Earlier exchanges no longer carry their tool results (see the design changes), so this is now
  only about the exchange in progress. Smaller search results would help most: `SearchTool`
  returns up to 1,500 characters for each of up to 20 files, and repeats files across searches.
- **Trim what is sent, not what is kept.** `trimToFit()` changes the conversation itself (as SD03
  draws it), so dropped exchanges also disappear from the saved conversation and from the GUI's
  view of it. A better version would keep the full history and trim only the copy that
  `PromptBuilder.buildRequest()` sends. This changes behaviour the report describes, so log it in
  `docs/logs/design-changes.md`.
- **Tell the model that its earlier searches were removed.** A follow-up now sees the earlier
  requests and answers but not the tool calls and results behind them (see the design changes).
  Seen on 2026-11-08: asked why it had skipped some files, the model wrote that it hadn't searched
  that folder in this conversation and called its own earlier findings unverified. It had
  searched; the record was gone. The new answer was still built on fresh searches, so only the
  wording was wrong, but it reads as the model contradicting itself. A better version would say
  so in `PromptBuilder`'s instructions (earlier answers came from searches whose results are no
  longer shown; search again when the details matter), or leave a one-line note in place of each
  removed call, such as the query and how many files it returned. Low priority.

## The agent's search

- **Let the agent look inside a folder, and find a folder by name.** The agent's only way to find
  files is `search`, which matches file names and text, never the folder path, and no tool lists a
  folder's files. Seen on 2026-11-08: asked twice about "the other files" in a folder it had
  already found two files in, the agent could only repeat those two. A text file there was never
  found because no query matched its words. A CSV file and a file without an extension are
  indexed by name only (neither is a supported format), so only a query with a word from their
  names finds them. A better version would let the agent ask for everything under a folder:
  `SearchFilters.location` already filters inside `SearchIndex`, and `SearchTool` just doesn't
  expose it (see "The agent's search filters by type and date only" in the design changes).
  Indexing the path's words as a searchable field would let a query with the folder's name find
  it. Changes behaviour the report describes, so log it in `docs/logs/design-changes.md`.
- **Send the model back to search when it answers without searching.** `AgentController` takes
  any reply without tool calls as the final answer, even on the first step. Seen on 2026-11-12
  with the local model `qwen3:8b`: asked "What's my cumulative GPA?", it answered at step 1 with
  no tool calls, once saying no indexed file mentions GPA, although `findany search "cumulative
  GPA"` finds the transcript with the GPA in its best passage. The system prompt already says to
  use the tools and never answer from memory. The cloud model searched every time. A larger
  local model is the main fix (smaller search results would leave room for one). As a safeguard,
  when the first reply has no tool calls, `AgentController` could send the model back once with
  "search the files before answering", like the reformat request. Changes behaviour the report
  describes, so log it in `docs/logs/design-changes.md`.

## The answer format

- **Find out why the model sometimes writes its citation entries twice, then make the parser cope.**
  Now and then a reply holds the `[n] file:` entries right after the answer's text and then again
  under the `CITATIONS:` line. `ResponseParser.parseAnswer()` takes everything above the first
  `CITATIONS:` line as the answer, so the first copy is shown as part of the answer, in the CLI
  and in the GUI (where the `[n]` that starts each stray line even becomes a citation link), and
  it is saved with the conversation. Seen on 2026-11-08.
  - **Known so far.** It happens within one reply: `findany ask --verbose` shows no reformat
    request. Every run used the same model (`anthropic/claude-haiku-5.5` through OpenRouter).
    `LangChain4jClient` sets no temperature, so replies vary from run to run. On the test corpus
    it happened once in 53 runs: 41 on `testdata/corpus` and 12 on a copy enlarged with filler
    files until each run's search results were 58,000 to 103,000 characters. Questions that ask
    for a list of files (0 of 8), bulleted answers and a long history each failed to trigger it.
    On Amar's own documents, with a question about last year's taxes and a follow-up, it happened
    3 times out of 3.
  - **Not explained.** On those documents, the three runs before the conversation change of
    2026-11-08 (`fbb0b7e`, "Keep requests and answers in a conversation, not old tool results")
    were clean, and the three after it were duplicated. That change does nothing to the first
    reply of a new conversation, and the 53 corpus runs used the build that includes it, so it is
    probably a coincidence of six runs. It hasn't been tested.
  - **Planned test, on the real documents (Amar runs it).** Ask the tax question five times with
    the current build, then five times with the build of the commit before `fbb0b7e`, and count
    the replies that have a line starting `[n] file:` above the `CITATIONS:` line (in the
    `answer` of `ask --json`, any such line is a stray one). A similar rate on both builds clears
    the change and leaves the documents' content and the model's variation as the cause. A rate
    that differs means the change reaches the first reply in a way not yet understood.
  - **Fixes to choose from once the cause is known.** Have the parser accept entries wherever
    they are, remove them from the text and merge identical duplicates, which would also let a
    reply that only forgot the `CITATIONS:` line parse without a reformat request. Reword the
    format instructions in `PromptBuilder`. Set a low temperature. The model does this on its own
    and only sometimes, so wording alone can't rule it out. A parser change alters the answer
    format rules in the design changes, so log it there and in `docs/logs/test-failures.md`.

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

## GUI

- **Render the answer's Markdown.** Models write their answers in Markdown (headings, `**bold**`,
  bullet lists, numbered lists, code spans), and `DetailPane.showAnswer()` shows that text as it
  is, so the marks appear literally in the pane (seen by Amar on 2026-11-07 with a tax question
  through OpenRouter). The `[n]` citation markers are the only part that is interpreted. Options:
  render a small Markdown subset into `Text` nodes with styles (bold, italic, code, headings as
  larger text, list items with a bullet and indent), keeping the citation links; or ask the model
  for plain text in `PromptBuilder`, which models don't reliably obey; or show the answer in a
  `WebView`, which needs the `javafx-web` module and loses the citation links unless they are
  rebuilt as HTML. The first keeps the design (one `TextFlow` per answer) and is the likely choice.
  The CLI prints the same text, so a plain-text rendering there is unaffected.

- **Let the settings dialog's long texts wrap instead of squeezing the controls.** Seen by Amar on
  2026-11-10: after "Test connection", the result ("Connected. New conversations use the cloud
  model …") was cut with "…" and shrank the Backend, Model and Test connection controls to "…"
  too, until the window was widened. The result label sits in the same row as the button, inside
  a grid column that has no width of its own. A better version puts the result on its own row with
  a maximum width so it wraps, or gives the grid's columns fixed widths.
- **Hide the privacy log in the settings dialog until the user asks for it.** It opens with the
  newest requests in full view, which someone recording a video or sharing their screen wouldn't
  want (Amar, 2026-11-10). A better version shows a "Show privacy log" button, or a collapsed
  pane, and loads the entries only when it is opened.
- **The GUI runs on XWayland, not Wayland.** On a Wayland desktop the window is an X11 client
  (Amar, 2026-11-10). This can't be changed from the app: JavaFX's Linux backend is GTK on X11,
  and the OpenJFX maintainers say JavaFX is supported on Wayland through XWayland, as AWT is;
  a native Wayland backend exists only as a prototype
  ([openjfx-dev, Dec 2024](https://mail.openjdk.org/pipermail/openjfx-dev/2024-December/051175.html),
  [openjfx-dev, May 2025](https://mail.openjdk.org/pipermail/openjfx-dev/2025-May/054097.html),
  [JDK-8326710](https://bugs.openjdk.org/browse/JDK-8326710)). Nothing to do until OpenJFX ships
  one; check again with each JavaFX release.

## Privacy

- **Withhold only the local-only entries of a search result, not the whole message.** A search
  result lists several files in one text, and `RedactingLlmClient` replaces the whole message
  when any one of them is in a local-only folder (as SD04 draws it), so the other files in that
  result are hidden from the cloud model too. Seen on 2026-11-10: a search that matched a private
  file also matched a note elsewhere, and both were withheld. A better version needs the tool
  result to carry per-file structure (a part per file with its source), so the decorator can drop
  one entry and keep the rest; parsing the search tool's text layout in the decorator would be
  brittle. Changes SD04, so log it in `docs/logs/design-changes.md`.
- **Keep sensitive files from reaching the model through search results.** Every search result
  carries each file's best passage, so the model sees file text without opening a file, and for a
  very small file that passage is the whole file. Seen in real use in November 2026: a small text
  file holding a code, named for what it was, came back from a search, and its content went to
  the cloud model. The built-in exclusions go by name and location (`.ssh`, `.env`, `*.pem`,
  `*.key`, ...) and didn't match it. Redaction (F14) won't fix this either: it is a fixed set of
  patterns for structured data such as card numbers, and a short code matches none of them. Until
  something better exists, the user has to exclude such a file by hand (`findany exclude add`).
  - **Amar's idea:** have a model judge from the file name alone whether a file looks sensitive.
    Such a file isn't indexed, and the user is told and asked to exclude it explicitly (or to
    allow it).
  - **Its limit:** a secret in a file whose name says nothing (a password in `notes.txt`) can't
    be told apart from a harmless string, by a model or by patterns. Nothing here covers that.
  - **Things to weigh when designing it:** a fixed list of name words (password, secret, access
    code, token, credentials, recovery, backup codes) would catch the obvious cases with no model
    call and could run first. The name check should use a local model or no model, since sending
    every file name to a cloud model is its own disclosure. One model call per new file is slow
    at indexing time, so names would be judged in batches. A flagged file could stay findable by
    name while its text never leaves the machine, like an unsupported format today. Whatever is
    chosen changes F01 and F14 as the report describes them, so log it in
    `docs/logs/design-changes.md`.

## Extraction

- **Stop Tika appending PDF link annotations to page text.** PDFBox adds every link annotation's
  URL after a page's visible text. PDFs printed from a browser (Firefox + cairo) carry one
  annotation per visual fragment of a link, so the same URL repeats up to a dozen times per page,
  bloating chunks and skewing keyword scores toward link-heavy pages (seen on a job posting with
  79 annotations over 3 pages). Fix: set the PDF parser's `extractAnnotationText` to false in the
  extractor's Tika config (or drop duplicate URLs per page). Changes what F02 extracts, so log it
  in `docs/logs/design-changes.md`.
