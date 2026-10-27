# Design changes

Source for sections 3 and 4 of the Stage 2 report. Every difference between the Stage 1 design
and the code needs an entry here, written when the change is made. The Stage 2 instructions say
unexplained differences aren't acceptable.

Each change also needs the class, use-case or sequence diagrams updated where it makes them wrong
(UMLet only). Keep changes few: each one costs diagram work.

## Templates

**Feature change** (added, replaced, removed, or substantially modified):

```
### Fxx: short title (status: Modified / Replaced / Removed / New / Not implemented)
1. Original Stage 1 feature and ID (or "New"):
2. Implemented feature and its behavior:
3. What changed and why:
4. How the user need is still met, or how the scope changed:
5. Affected use cases, classes, methods, and design patterns:
Diagrams updated:
```

**Design-pattern change:**

```
### Pattern: short title
6. Original pattern (if any):
7. Pattern actually implemented:
8. Participating classes and their roles:
9. Design problem addressed:
10. Why it's appropriate and how it affects the architecture:
```

**Other architecture or class change:** Original design → Implemented change → Reason → Impact.

## Decided before implementation

### Cloud model reached through OpenRouter
- **Original design:** `LangChain4jClient` adapts LangChain4j's Anthropic chat model; the cloud
  backend is Anthropic's API directly.
- **Implemented change:** `LangChain4jClient` adapts LangChain4j's `OpenAiChatModel` pointed at
  OpenRouter's OpenAI-compatible API. Cloud models (including Claude models) are chosen by their
  OpenRouter model ID. Ollama keeps LangChain4j's Ollama module.
- **Reason:** payment for Anthropic Console credits couldn't be completed.
- **Impact:** F13, F14. Requests now pass through a second company (OpenRouter) before the model
  provider; redaction and the privacy log still apply to everything sent. OpenRouter is set to
  not log prompts and to avoid providers that train on data. The Adapter pattern is unchanged.

### API key in a local `.env` file
- **Original design:** F13 has the user enter the API key in Settings.
- **Implemented change:** the key is read from `.env` (git-ignored) or the environment;
  `.env.example` is committed with placeholders.
- **Reason:** the Stage 2 instructions require keys in a local configuration file.
- **Impact:** F13, `SettingsDialog`, `ModelSettings`.

### Files without pages: DOCX, TXT, MD
- **Original design:** each chunk keeps its page or slide number.
- **Implemented change:** PDF pages and PowerPoint slides are numbered; DOCX, TXT and MD are one
  unnumbered "page", so their citations name the file but no page. `CitationVerifier` searches the
  whole file for them.
- **Reason:** these formats store no page boundaries (verified with Tika 4.1.0).
- **Impact:** F02, F09, F11; `Chunk`, `Citation`, `CitationVerifier`.

### PowerPoint slide order corrected in the extractor
- **Original design:** not covered.
- **Implemented change:** `IsolatedTextExtractor` reorders slides using the deck's own slide list.
- **Reason:** Tika 4.1.0 returns slides sorted by internal ID, not deck order (verified), which
  would make slide-number citations wrong.
- **Impact:** F02, F09; `IsolatedTextExtractor` gains a small helper.

## Made during implementation

### `ContentStore` postponed (2026-10-25, Slice 1)
- **Original design:** `Indexer.indexFile()` looks the content hash up in `ContentStore` and
  reuses stored chunks and embeddings for renamed, moved or copied files; `pruneUnreferenced()`
  cleans it up.
- **Implemented change:** Stage 2 has no `ContentStore`. `Indexer` takes no `contentStore` (nor
  `embeddingModel`, until F05) and extracts every new path again; `IndexedFile.contentHash` is
  still computed and stored, so adding the store later changes nothing in the index.
- **Reason:** time. Extraction of a renamed file costs tens of milliseconds, and the store only
  matters once embeddings (F05) make re-processing expensive.
- **Impact:** F02, F03; `Indexer`, `FindAnythingService`. SD01's `ContentStore` lifeline is not
  exercised. Diagrams to update at the freeze if the store stays out.

### Bounded retry for locked files (2026-10-25, Slice 1)
- **Original design:** F03: "A file is locked while it's being written: it's retried after another
  quiet period."
- **Implemented change:** `Indexer.indexFile()` retries a file that can't be read up to three
  times (`MAX_READ_RETRIES`), then marks it `FAILED` so `index status --failed` shows it.
- **Reason:** without a bound, a permanently locked file would be retried forever and keep the
  status from ever reaching "up to date".
- **Impact:** F03, `Indexer`. The next rescan (startup, `index rebuild`) tries the file again.

### Supported formats checked before parsing (2026-10-25, Slice 1)
- **Original design:** F02 lists "supported formats such as plain text, PDF, Word, and
  PowerPoint"; unsupported formats are indexed by name only.
- **Implemented change:** `IsolatedTextExtractor` detects the type in-process from the file's
  contents (Tika's detector, no parsing) and only forks the parser for plain text, Markdown, PDF,
  Word (.docx) and PowerPoint (.pptx). Everything else is `METADATA_ONLY` without being parsed.
- **Reason:** parsing arbitrary formats (archives, video, spreadsheets) in the child process
  costs time and memory for text nobody searches; detection takes about a millisecond.
- **Impact:** F02, `IsolatedTextExtractor`. CSV and images are indexed by name only (as
  `testdata/FACTS.md` expects).

### Small members added for the CLI and tests (2026-10-25, Slice 1)
- **Original design:** the class diagrams leave out constructors, most getters and small
  helpers.
- **Implemented change:** members not drawn in the diagrams: `SearchFolder.removeRule()`,
  `IndexConfig.removeGlobalRule()`, `findFolderContaining()` and the `appDataFolder` constructor
  argument (so the app's own folder is excluded); `FindAnythingService.removeExclusion()`,
  `listFailedFiles()`, `countIndexedFiles()`, `getIndexConfig()`, a `search()` overload with a
  limit; `SearchIndex.listFiles(ExtractionStatus)`, `countFiles()`; `Indexer.removeStatusListener()`;
  `SearchFilters.NONE` and `isEmpty()`; `ExtractedDocument.numberedPages`; `ChunkHit.chunk` is
  null for a file found by name only; `IndexConfig.findFolder()` and `SearchIndex.getFile()`
  return `Optional`. `IndexerState` has `STOPPED` after `STOPPING`.
- **Reason:** `findany exclude remove` and `index status --failed` (F01, F02) need them, and the
  facade must know which folder a path belongs to.
- **Impact:** none on the patterns. The class diagram gets these at the freeze.

## Pending (decide during implementation; write the entry if it happens)

- F03: live file watching replaced by rescans at startup, on demand, and on a timer.
- F04, F05, F10: Tier 2 features that may end up *Not implemented* in Stage 2.
- GUI: conversation drawer, side-by-side comparison, privacy log viewer, editing exclusions in the
  GUI (CLI-only).
