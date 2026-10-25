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

## Pending (decide during implementation; write the entry if it happens)

- F03: live file watching replaced by rescans at startup, on demand, and on a timer.
- F02/F03: `ContentStore` postponed; renamed or copied files are extracted again.
- F04, F05, F10: Tier 2 features that may end up *Not implemented* in Stage 2.
- GUI: conversation drawer, side-by-side comparison, privacy log viewer, editing exclusions in the
  GUI (CLI-only).
