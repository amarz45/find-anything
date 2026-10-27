# Feature-to-implementation traceability

Source for sections 2, 3, 6 and 7 of the Stage 2 report. Update a row whenever a feature's code
lands (the Sunday routine), so the report is assembly rather than reconstruction.

Statuses: *Planned*, *In progress*, *Implemented*, *Modified*, *Replaced*, *Not implemented*.
Every feature not plainly *Implemented* needs an entry in `logs/design-changes.md`.

## Features

Tier 1 is the Stage 2 target; Tier 2 is done in the order listed if time allows.

| Feature | Tier | Stage 1 classes | Status | Implemented files and key methods | Deterministic / AI / hybrid | Video |
|---|---|---|---|---|---|---|
| F01 Search folder management | 1 | MainWindow, GuiController, FindAnythingService, IndexConfig, SearchFolder, ExclusionRule, SettingsStore, FileWatcher, Indexer | In progress (CLI done; GUI add/remove in Slice 3) | `config/IndexConfig.java` (`addFolder()`, `removeFolder()`, `addGlobalRule()`, `isIncluded()`, built-in rules), `config/ExclusionRule.java` (`parse()`, `matches()`), `config/SearchFolder.java` (`contains()`, `isExcluded()`, `setPaused()`), `config/SettingsStore.java` (`loadIndexConfig()`, `saveIndexConfig()`), `core/FindAnythingService.java` (`addSearchFolder()`, `removeSearchFolder()`, `setFolderPaused()`, `addExclusion()`, `removeExclusion()`), `ui/CliApp.java` (`folders`, `exclude`) | Deterministic | |
| F02 Text extraction across formats | 1 | Indexer, TextExtractor, IsolatedTextExtractor, Chunker, ContentStore, SearchIndex, IndexedFile, Chunk | Implemented (ContentStore postponed, see design changes) | `indexing/TextExtractor.java`, `indexing/IsolatedTextExtractor.java` (`extract()`, format detection, page/slide split, slide order), `indexing/Chunker.java` (`split()`), `indexing/Indexer.java` (`indexFile()`: hash, status per failure case), `storage/SearchIndex.java` (`put()`), `storage/IndexedFile.java`, `storage/Chunk.java`, `storage/ExtractionStatus.java`; `ui/CliApp.java` (`index status --failed`) | Deterministic | |
| F03 Incremental index updates | 1 (rescans); 2 (live watcher) | FileWatcher, ChangeQueue, IndexConfig, Indexer, SearchIndex, ContentStore, IndexStatusListener, GuiController | In progress (rescans at startup and on request, status observers and the read-only second process done; live watcher is Tier 2) | `indexing/ChangeQueue.java` (`offer()`, `takeReady()`), `indexing/Indexer.java` (`start()`, `requestUpdate()`, `rebuild()`, `rescanFolder()`, `notifyStatus()`), `indexing/IndexStatus.java`, `indexing/IndexStatusListener.java`, `indexing/IndexerState.java`, `storage/SearchIndex.java` (`open()` write lock or read-only, `remove()`, `commit()`, `refresh()`), `core/FindAnythingService.java` (`open()`, `close()`, `getIndexStatus()`, `rebuildIndex()`, `addStatusListener()`), `ui/CliApp.java` (`index status`, `index rebuild`, catch-up before every command) | Deterministic | |
| F04 Filters | 2 (#2) | GuiController, FindAnythingService, SearchFilters, IndexConfig, SearchIndex | Planned (the `SearchFilters` record with `validate()`/`matches()` exists and `SearchIndex` already applies filters inside its queries; CLI options and GUI remain) | `storage/SearchFilters.java`, `storage/SearchIndex.java` (filter clauses) | Deterministic | |
| F05 Semantic search | 2 (#1) | SearchEngine, EmbeddingModel, LocalEmbeddingModel, SearchIndex, Indexer, ContentStore | Planned | | Hybrid (local embedding model) | |
| F06 Instant hybrid search | 1 (keyword part) | MainWindow, GuiController, FindAnythingService, SearchEngine, SearchIndex, SearchResult, ResultsView | In progress (keyword search and CLI done; GUI in Slice 3) | `search/SearchEngine.java` (`search()`, `fuseRankings()`), `search/SearchResult.java`, `storage/SearchIndex.java` (`keywordSearch()`, `listFiles()`, accent folding, literal special characters), `storage/ChunkHit.java`, `core/FindAnythingService.java` (`search()`), `ui/CliApp.java` (`search`) | Deterministic until F05 | |
| F07 Natural-language search | 1 | GuiController, FindAnythingService, LlmClientFactory, AgentController, PromptBuilder, LlmClient, ToolRegistry, Tool, SearchTool, SearchEngine, DetailPane | Planned | | AI-based | |
| F08 Question answering across files | 1 | AgentController, ToolRegistry, ReadExcerptTool, SearchIndex, ResponseParser, Answer, Citation, DetailPane, ResultsView | Planned | | AI-based | |
| F09 Citation verification | 1 | CitationVerifier, Answer, Citation, SearchIndex, PromptBuilder | Planned | | Deterministic | |
| F10 Document comparison | 2 (#4) | ResultsView, GuiController, FindAnythingService, DocumentComparator, DiffResult, AgentController, PromptBuilder, CompareTool, DetailPane | Planned | | Hybrid | |
| F11 Result preview with highlighting | 1 | ResultsView, GuiController, FindAnythingService, SearchIndex, Indexer, DetailPane | In progress (CLI `show` done; GUI pane in Slice 3) | `core/FindAnythingService.java` (`preview()`, `joinChunks()`, `findHighlights()`), `core/Preview.java`, `storage/SearchIndex.java` (`getChunks()`, `getFile()`), `ui/CliApp.java` (`show --highlight`) | Deterministic | |
| F12 Conversation follow-ups | 1 | Conversation, ConversationStore, AgentController, PromptBuilder, FindAnythingService, GuiController | Planned | | AI-based | |
| F13 Model backend selection and privacy log | 1 | SettingsDialog, GuiController, FindAnythingService, LlmClientFactory, LlmClient, LangChain4jClient, LoggingLlmClient, PrivacyLog, SettingsStore, ModelSettings | Planned | | Deterministic | |
| F14 Sensitive-data protection | 1 | RedactingLlmClient, Redactor, SensitivePattern, FolderPolicy, IndexConfig, LlmClientFactory, GuiController, FindAnythingService | Planned | | Deterministic | |

## Design patterns

Stage 2 needs at least five distinct patterns, evident in the code.

| Pattern | Stage 1 participants | Status | Implemented files |
|---|---|---|---|
| Facade | FindAnythingService (facade); GuiController, CliApp (clients) | Implemented (GuiController client in Slice 3) | `core/FindAnythingService.java`; client `ui/CliApp.java` |
| Observer | Indexer (subject), IndexStatusListener (observer), GuiController (concrete observer), IndexStatus | In progress (subject and observer interface done; GuiController in Slice 3) | `indexing/Indexer.java` (`addStatusListener()`, `notifyStatus()`), `indexing/IndexStatusListener.java`, `indexing/IndexStatus.java` |
| Command | Tool (command), SearchTool, ReadExcerptTool, CompareTool, ToolRegistry (invoker), AgentController (client) | Planned | |
| Adapter | LlmClient, TextExtractor, EmbeddingModel (targets); LangChain4jClient, IsolatedTextExtractor, LocalEmbeddingModel (adapters) | In progress (TextExtractor/IsolatedTextExtractor done; LlmClient/LangChain4jClient in Slice 2) | `indexing/TextExtractor.java`, `indexing/IsolatedTextExtractor.java` (Tika stays inside); `storage/SearchIndex.java` keeps Lucene inside the same way |
| Decorator | LlmClientDecorator, RedactingLlmClient, LoggingLlmClient around LangChain4jClient | Planned | |
| Factory | LlmClientFactory | Planned | |
| MVC | Views, GuiController, FindAnythingService | Planned | |

## Weekly commit summary

At least 5 meaningful *code* commits per period (implementation or test code; not docs, formatting
or empty commits), pushed during the period, Toronto time. Missing any period costs 5 points once.

| Period | Dates | Commits (links) | Count |
|---|---|---|---|
| 1 | Oct 20–26 | [8209963](https://github.com/amarz45/find-anything/commit/8209963021fb3b575c2814139f63cddcdd7890bd), [ade7514](https://github.com/amarz45/find-anything/commit/ade7514a3b350ef0a1ef8b294de8596b04de988b), [5f3c464](https://github.com/amarz45/find-anything/commit/5f3c464315df93879d7f5d4e269c4a33d2d9fccf), [391a21b](https://github.com/amarz45/find-anything/commit/391a21b325bcb68dd102a6ab26bd1eef04893a92), [3ae1cc9](https://github.com/amarz45/find-anything/commit/3ae1cc93c055709c2ceafb08460a810f4f6fb8bc), [67e0752](https://github.com/amarz45/find-anything/commit/67e07528a4913e57b04a11f906aaa1857210c18b), [57d2b06](https://github.com/amarz45/find-anything/commit/57d2b0612cf9ff99e6e06e0c5f6c123042235d51), [46ddd7e](https://github.com/amarz45/find-anything/commit/46ddd7eb9d1a9a30a23281e9b81323d5f0e00ad5) | 8 |
| 2 | Oct 27–Nov 2 | | |
| 3 | Nov 3–9 | | |
