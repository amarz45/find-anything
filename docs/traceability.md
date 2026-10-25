# Feature-to-implementation traceability

Source for sections 2, 3, 6 and 7 of the Stage 2 report. Update a row whenever a feature's code
lands (the Sunday routine), so the report is assembly rather than reconstruction.

Statuses: *Planned*, *In progress*, *Implemented*, *Modified*, *Replaced*, *Not implemented*.
Every feature not plainly *Implemented* needs an entry in `logs/design-changes.md`.

## Features

Tier 1 is the Stage 2 target; Tier 2 is done in the order listed if time allows.

| Feature | Tier | Stage 1 classes | Status | Implemented files and key methods | Deterministic / AI / hybrid | Video |
|---|---|---|---|---|---|---|
| F01 Search folder management | 1 | MainWindow, GuiController, FindAnythingService, IndexConfig, SearchFolder, ExclusionRule, SettingsStore, FileWatcher, Indexer | Planned | | Deterministic | |
| F02 Text extraction across formats | 1 | Indexer, TextExtractor, IsolatedTextExtractor, Chunker, ContentStore, SearchIndex, IndexedFile, Chunk | Planned | | Deterministic | |
| F03 Incremental index updates | 1 (rescans); 2 (live watcher) | FileWatcher, ChangeQueue, IndexConfig, Indexer, SearchIndex, ContentStore, IndexStatusListener, GuiController | Planned | | Deterministic | |
| F04 Filters | 2 (#2) | GuiController, FindAnythingService, SearchFilters, IndexConfig, SearchIndex | Planned | | Deterministic | |
| F05 Semantic search | 2 (#1) | SearchEngine, EmbeddingModel, LocalEmbeddingModel, SearchIndex, Indexer, ContentStore | Planned | | Hybrid (local embedding model) | |
| F06 Instant hybrid search | 1 (keyword part) | MainWindow, GuiController, FindAnythingService, SearchEngine, SearchIndex, SearchResult, ResultsView | Planned | | Deterministic until F05 | |
| F07 Natural-language search | 1 | GuiController, FindAnythingService, LlmClientFactory, AgentController, PromptBuilder, LlmClient, ToolRegistry, Tool, SearchTool, SearchEngine, DetailPane | Planned | | AI-based | |
| F08 Question answering across files | 1 | AgentController, ToolRegistry, ReadExcerptTool, SearchIndex, ResponseParser, Answer, Citation, DetailPane, ResultsView | Planned | | AI-based | |
| F09 Citation verification | 1 | CitationVerifier, Answer, Citation, SearchIndex, PromptBuilder | Planned | | Deterministic | |
| F10 Document comparison | 2 (#4) | ResultsView, GuiController, FindAnythingService, DocumentComparator, DiffResult, AgentController, PromptBuilder, CompareTool, DetailPane | Planned | | Hybrid | |
| F11 Result preview with highlighting | 1 | ResultsView, GuiController, FindAnythingService, SearchIndex, Indexer, DetailPane | Planned | | Deterministic | |
| F12 Conversation follow-ups | 1 | Conversation, ConversationStore, AgentController, PromptBuilder, FindAnythingService, GuiController | Planned | | AI-based | |
| F13 Model backend selection and privacy log | 1 | SettingsDialog, GuiController, FindAnythingService, LlmClientFactory, LlmClient, LangChain4jClient, LoggingLlmClient, PrivacyLog, SettingsStore, ModelSettings | Planned | | Deterministic | |
| F14 Sensitive-data protection | 1 | RedactingLlmClient, Redactor, SensitivePattern, FolderPolicy, IndexConfig, LlmClientFactory, GuiController, FindAnythingService | Planned | | Deterministic | |

## Design patterns

Stage 2 needs at least five distinct patterns, evident in the code.

| Pattern | Stage 1 participants | Status | Implemented files |
|---|---|---|---|
| Facade | FindAnythingService (facade); GuiController, CliApp (clients) | Planned | |
| Observer | Indexer (subject), IndexStatusListener (observer), GuiController (concrete observer), IndexStatus | Planned | |
| Command | Tool (command), SearchTool, ReadExcerptTool, CompareTool, ToolRegistry (invoker), AgentController (client) | Planned | |
| Adapter | LlmClient, TextExtractor, EmbeddingModel (targets); LangChain4jClient, IsolatedTextExtractor, LocalEmbeddingModel (adapters) | Planned | |
| Decorator | LlmClientDecorator, RedactingLlmClient, LoggingLlmClient around LangChain4jClient | Planned | |
| Factory | LlmClientFactory | Planned | |
| MVC | Views, GuiController, FindAnythingService | Planned | |

## Weekly commit summary

At least 5 meaningful *code* commits per period (implementation or test code; not docs, formatting
or empty commits), pushed during the period, Toronto time. Missing any period costs 5 points once.

| Period | Dates | Commits (links) | Count |
|---|---|---|---|
| 1 | Oct 20–26 | | |
| 2 | Oct 27–Nov 2 | | |
| 3 | Nov 3–9 | | |
