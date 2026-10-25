# Apache Tika 4.1.0: tested behavior

Tested 2026-10-16 with the official `tika-app-4.1.0.zip` jars on Linux (JDK 21, a 2-core cloud machine),
plus one run inside a Gradle 8.14.3 test. Sample files were generated for the test (PDF, DOCX, PPTX, MD,
image-only PDF, password-protected PDF, broken PDFs, zip, 1,500-page PDF).
**Not tested:** Windows, macOS, Gradle 9, jpackage, files saved by Microsoft PowerPoint.

## Dependency

One Gradle line: `implementation("org.apache.tika:tika-pipes-fork-parser:4.1.0")`. Per its pom and
Tika's docs, it brings the standard parsers and the file-system plugin jar (Maven Central was not
reachable from the test machine, so the Gradle download itself is untested). No plugins folder is
needed: the plugin is found on the class path. Tika logs one harmless warning ("no plugins directory found").
Don't point `setPluginsDir()` at an empty folder to silence it. That works in 4.1.0, but on the
main branch (future 4.2.0) a plugins folder turns off class-path plugins.

## Settings we use

| Setting | Why |
|---|---|
| `setJavaPath(<java.home>/bin/java[.exe])` | Default is `java` from PATH. With no `java` on PATH the child fails to start (`FAILED_TO_INITIALIZE`). |
| `setHandlerType(XML)` | Keeps `<div class="page">` (PDF) and `<div class="slide-content">` (PPTX). Plain TEXT loses page boundaries. |
| `setUserConfigPath(file containing { "text-recognizers": [] })` | Turns OCR off. Without it, OCR turns on by itself on any machine with Tesseract installed (it even OCR'd the preview thumbnail stored inside the test DOCX and PPTX). A misspelled key fails at startup with "Unknown configuration key(s)". |
| `setMaxEmbeddedCount(0)` | Skips thumbnails and files embedded inside documents. The document's own text, footers and speaker notes are unaffected. Adds a harmless `tk:exception:embedded-resource-limit-reached=true`. |
| `setWriteLimit(10_000_000)` | Caps text per file. Sets `tk:exception:write-limit-reached=true`; the XHTML then stops mid-document (not well-formed), so the page splitter must keep what it read. A 1,500-page PDF gave 1,367 pages under this cap. |
| `addJvmArg("-Xmx1g")` | Without it the child's max heap is 60% of RAM. Measured child memory: ~220–260 MB. |
| `addJvmArg("-Dlog4j2.configurationFile=classpath:tika-fork-log4j2.xml")` | The child logs every file at INFO to the app's stderr. A resource in our jar works because the child gets our class path. |
| Timeouts: keep defaults | See "Timeouts" below. |

## Reading a result (`PipesForkResult`)

1. `isProcessCrash()` → `OOM`, `TIMEOUT` or `UNSPECIFIED_CRASH`. The app keeps running; the child restarts on the next parse (~5 s).
2. `!isSuccess()` → e.g. `FETCH_EXCEPTION` for a file that no longer exists.
3. **`isSuccess()` is also true for files Tika could not read** (status `PARSE_SUCCESS_WITH_EXCEPTION`):
   password-protected, corrupt, and empty files. Check `TikaCoreProperties.CONTAINER_EXCEPTION`
   (`tk:exception:container-exception`) and null content. `TikaCoreProperties.IS_ENCRYPTED`
   (`tk:encrypted`) is `true` for a PDF that needs a password.
4. Content is in `getMetadataList().get(0).get(TikaCoreProperties.TIKA_CONTENT)`.
   Content type: `HttpHeaders.CONTENT_TYPE` (`Metadata.CONTENT_TYPE` no longer exists in 4.x).

## Formats

- **PDF:** one `<div class="page">` per page. A truncated PDF returns the pages it could read, with
  no error. A PDF that only restricts copying (owner password) is read normally. An image-only
  PDF returns empty pages when OCR is off. `pdf:ocr-page-count` is set even with OCR off; ignore it.
- **PPTX:** one `<div class="slide-content">` per slide; speaker notes come right after their
  slide's div. **Slide order bug:** Tika lists slides sorted by their internal relationship id
  as text (`rId10` before `rId7`), not by the deck's real order (`<p:sldIdLst>` in
  `ppt/presentation.xml`). A 12-slide python-pptx deck came out as 4–12, 1–3; a deck whose last
  slide was moved to the front came out in creation order. LibreOffice renumbers ids on save,
  so LibreOffice-saved decks came out right. Tika 3.3.2's default PPTX extractor has the same
  loop (source read, not run). Fix: reorder using `ppt/presentation.xml` and `ppt/_rels/presentation.xml.rels`
  (see `SlideOrder` in `ExtractorDemo.java`).
- **DOCX:** no page markers. Headings come as `<h1>`…, the footer as `<p class="footer">`.
  `meta:page-count` is whatever the file's saved properties say (a 2-page test file said 1).
- **MD/TXT:** one block of text; UTF-8 including emoji came through correctly.

## Process behavior (2-core cloud machine)

- Child start: ~4.4–5 s. Warm parses: small PDF 15–50 ms, DOCX 30–40 ms, PPTX 40–70 ms,
  MD 6–10 ms; 1,500-page PDF 3.3–5 s.
- The child exits after ~60 s idle (`socketTimeoutMillis`); the next file pays the start again.
- First PDF on a machine builds PDFBox's font cache at `~/.pdfbox.cache` (0.4–11 s here).
- Interrupting the calling thread: `parse()` throws `InterruptedException` within ~0.5 s and the
  interrupt flag stays set. The next parse replaces the child (~5 s).
- `close()` stops the child in ~30 ms. If the app is killed (`kill -9`), the child exits within
  ~2 s by itself and deletes its temp folder.
- The child's CPU use is capped automatically (`-XX:ActiveProcessorCount`, cores minus 2, minimum 2).

## Timeouts

Defaults: no-progress limit 120 s, total limit 1 h. The total limit is cooperative (it skips
remaining embedded files); the hard kill happens at total + no-progress time. PDFs report no
progress while being parsed, so for a PDF the no-progress limit is effectively the time limit.
Keep the defaults.

## Pitfalls

- **Linux locale:** if the app runs without a UTF-8 locale (`sun.jnu.encoding` not UTF-8, e.g.
  launched with no `LANG`), every file with a non-ASCII name crashes the child
  (`InvalidPathException`), and each crash costs a ~5 s restart. Desktop sessions normally set
  `LANG`. Check `System.getProperty("sun.jnu.encoding")` at startup and warn.
- **Class path:** the child is started with the parent's `java.class.path` (Tika source:
  `PerClientServerManager.writeArgFile`). Keep the app on the class path (no `module-info.java`).
  Inside a Gradle 8.14.3 test, `java.class.path` held the full test class path and parsing worked.

## `src/main/resources/tika-fork-log4j2.xml` (errors only from the child)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
  <Appenders>
    <Console name="console" target="SYSTEM_ERR">
      <PatternLayout pattern="%-5p [fork] %c{1} %m%n"/>
    </Console>
  </Appenders>
  <Loggers>
    <Root level="error"><AppenderRef ref="console"/></Root>
  </Loggers>
</Configuration>
```

## Re-check against the test corpus (2026-10-23)

`ExtractorDemo.java` (the reference in this folder) was run on every file in `testdata/corpus/`:

- Every fact listed in `testdata/FACTS.md` came out on the stated page or slide, including slide 10
  of the 12-slide `lecture-week-03-sorting.pptx` (which raw Tika puts out of order).
- `edge/empty.txt` failed as unreadable (zero bytes); `edge/password-protected.pdf` failed as
  password-protected.
- `edge/truncated.pdf` came back as 3 empty pages with no error. `edge/scanned-receipt.pdf` and
  `edge/photo.png` came back with empty text. Treat "no text at all" as its own status.
- The `.ssh` placeholder and the `~$` lock file were extracted as plain text. Tika doesn't know
  about credentials or lock files, so the built-in exclusions in `IndexConfig` must skip them
  before extraction.
- Pure-ASCII text files are reported as `windows-1252`. That's harmless for ASCII; an earlier test
  showed UTF-8 text with accents and emoji detected correctly.

`ExtractorDemo.java` is a reference only: it's in `docs/`, outside the build. Adapt its pieces
(`IsolatedTextExtractor`, `PageSplitter`, `SlideOrder`) into the `indexing` package.
