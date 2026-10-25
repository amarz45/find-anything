package io.github.amarz45.findanything.ui;

import io.github.amarz45.findanything.config.ExclusionRule;
import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.config.SearchFolder;
import io.github.amarz45.findanything.config.SettingsStore;
import io.github.amarz45.findanything.core.FindAnythingService;
import io.github.amarz45.findanything.core.Preview;
import io.github.amarz45.findanything.indexing.ChangeQueue;
import io.github.amarz45.findanything.indexing.Chunker;
import io.github.amarz45.findanything.indexing.IndexStatus;
import io.github.amarz45.findanything.indexing.IsolatedTextExtractor;
import io.github.amarz45.findanything.indexing.TextExtractor;
import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.IndexMode;
import io.github.amarz45.findanything.storage.IndexedFile;
import io.github.amarz45.findanything.storage.SearchFilters;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.bridge.SLF4JBridgeHandler;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * The {@code findany} command line. With no arguments it prints help; every subcommand first
 * catches up on files changed since the last run (F03), then does its work through {@link
 * FindAnythingService} and waits for the result. {@code main} is the composition root: it resolves
 * the data folder ({@code FINDANY_HOME}) and builds the service and its collaborators.
 */
@Command(
        name = "findany",
        mixinStandardHelpOptions = true,
        version = "findany 0.2.0",
        description = "Indexes your files, finds them, and answers questions about them.",
        synopsisSubcommandLabel = "COMMAND",
        subcommands = {
            CliApp.Folders.class,
            CliApp.Exclude.class,
            CliApp.Index.class,
            CliApp.Search.class,
            CliApp.Show.class
        })
public final class CliApp implements Callable<Integer> {

    /** Returned when a command fails; picocli returns 2 for a usage error. */
    static final int FAILURE = 1;

    private static final Duration PROGRESS_AFTER = Duration.ofSeconds(1);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final FindAnythingService findAnythingService;
    private boolean readOnlyNoteShown;
    @Spec CommandSpec spec;

    public CliApp(FindAnythingService findAnythingService) {
        this.findAnythingService = findAnythingService;
    }

    public static void main(String[] args) {
        // Lucene logs through java.util.logging; send that through SLF4J so log4j2.xml governs it.
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        int exitCode;
        Path home =
                resolveHome(
                        System.getenv(),
                        System.getProperty("os.name", ""),
                        System.getProperty("user.home"));
        try (FindAnythingService service = createService(home)) {
            exitCode = new CliApp(service).run(args);
        } catch (IOException e) {
            System.err.println("findany: " + e.getMessage());
            exitCode = FAILURE;
        }
        System.exit(exitCode);
    }

    /** Runs one command line and returns its exit code; never calls {@code System.exit}. */
    public int run(String[] args) {
        return run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
    }

    /** As {@link #run(String[])}, writing to the given streams (for tests). */
    public int run(String[] args, PrintWriter out, PrintWriter err) {
        CommandLine commandLine = new CommandLine(this);
        commandLine.setOut(out);
        commandLine.setErr(err);
        commandLine.setExecutionExceptionHandler(CliApp::reportFailure);
        return commandLine.execute(args);
    }

    /** {@code findany} alone prints help, like most command-line tools. */
    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    // ------------------------------------------------------------------ composition root

    /**
     * The app's data folder: {@code FINDANY_HOME} if set, otherwise the platform's app-data folder.
     * The built-in exclusions always skip it.
     */
    static Path resolveHome(Map<String, String> env, String osName, String userHome) {
        String configured = env.get("FINDANY_HOME");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String local = env.get("LOCALAPPDATA");
            Path base =
                    local != null && !local.isBlank()
                            ? Path.of(local)
                            : Path.of(userHome, "AppData", "Local");
            return base.resolve("FindAnything");
        }
        if (os.contains("mac")) {
            return Path.of(userHome, "Library", "Application Support", "FindAnything");
        }
        String xdg = env.get("XDG_DATA_HOME");
        Path base =
                xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(userHome, ".local", "share");
        return base.resolve("find-anything");
    }

    /** Builds the facade and everything behind it for one CLI run. */
    static FindAnythingService createService(Path home) throws IOException {
        SettingsStore settingsStore = new SettingsStore(home.resolve("settings.json"));
        IndexConfig indexConfig = settingsStore.loadIndexConfig();
        TextExtractor textExtractor =
                new IsolatedTextExtractor(
                        home.resolve("tika"), Duration.ofSeconds(120), 1024L * 1024 * 1024);
        // The CLI runs once and exits, so it needs no quiet period before indexing a changed file.
        ChangeQueue changeQueue = new ChangeQueue(Duration.ZERO);
        ExecutorService backgroundExecutor =
                Executors.newFixedThreadPool(
                        2,
                        runnable -> {
                            Thread thread = new Thread(runnable, "findany-background");
                            thread.setDaemon(true);
                            return thread;
                        });
        return new FindAnythingService(
                indexConfig,
                settingsStore,
                textExtractor,
                new Chunker(1_500),
                changeQueue,
                home.resolve("index"),
                backgroundExecutor);
    }

    // ------------------------------------------------------------------ shared steps

    /** Opens the index and says so once if this process only got read access. */
    private IndexMode openIndex(PrintWriter err) throws IOException {
        IndexMode mode = findAnythingService.open(false);
        if (mode == IndexMode.READ_ONLY && !readOnlyNoteShown) {
            readOnlyNoteShown = true;
            err.println(
                    "Note: another Find Anything process is updating the index; this one is read-only.");
        }
        return mode;
    }

    /** Waits until the index has caught up, showing progress when it takes more than a moment. */
    private void catchUp(PrintWriter err) throws InterruptedException {
        long started = System.nanoTime();
        int lastShown = -1;
        IndexStatus status;
        while (!(status = findAnythingService.getIndexStatus()).isUpToDate()) {
            if (System.nanoTime() - started > PROGRESS_AFTER.toNanos()
                    && status.pendingFiles() != lastShown) {
                lastShown = status.pendingFiles();
                err.print("\rIndexing... " + lastShown + " pending   ");
                err.flush();
            }
            Thread.sleep(100);
        }
        if (lastShown >= 0) {
            err.println();
        }
    }

    private void printSummary(PrintWriter out) throws IOException {
        IndexStatus status = findAnythingService.getIndexStatus();
        int files = findAnythingService.countIndexedFiles();
        out.printf(
                "Index up to date: %d file%s, %d failed.%n",
                files, files == 1 ? "" : "s", status.failedFiles());
    }

    private static int reportFailure(
            Exception e, CommandLine commandLine, CommandLine.ParseResult parseResult) {
        Throwable cause =
                e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        commandLine.getErr().println("findany: " + describe(cause));
        return FAILURE;
    }

    private static String describe(Throwable e) {
        if (e instanceof NoSuchFileException) {
            return "No such file or folder: " + e.getMessage();
        }
        if (e instanceof NotDirectoryException) {
            return "Not a folder: " + e.getMessage();
        }
        if (e instanceof AccessDeniedException) {
            return "Can't read " + e.getMessage();
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, value < 10 ? "%.1f %s" : "%.0f %s", value, units[unit]);
    }

    static String formatDate(Instant instant) {
        return DATE.format(instant.atZone(ZoneId.systemDefault()));
    }

    static String snippet(String text, int maxChars) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= maxChars ? oneLine : oneLine.substring(0, maxChars - 1) + "…";
    }

    // ------------------------------------------------------------------ findany folders

    /** {@code findany folders add|remove|pause|resume|list} (F01). */
    @Command(
            name = "folders",
            description = "Manage the folders that are searched.",
            synopsisSubcommandLabel = "COMMAND",
            subcommands = {
                Folders.Add.class,
                Folders.Remove.class,
                Folders.Pause.class,
                Folders.Resume.class,
                Folders.ListFolders.class
            })
    static final class Folders implements Callable<Integer> {
        @ParentCommand CliApp app;
        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(spec.commandLine().getOut());
            return 0;
        }

        @Command(name = "add", description = "Add a folder (searched recursively) and index it.")
        static final class Add implements Callable<Integer> {
            @ParentCommand Folders folders;
            @Spec CommandSpec spec;

            @Parameters(index = "0", paramLabel = "<folder>", description = "The folder to search.")
            Path folder;

            @Override
            public Integer call() throws Exception {
                PrintWriter out = spec.commandLine().getOut();
                folders.app.openIndex(spec.commandLine().getErr());
                SearchFolder added = folders.app.findAnythingService.addSearchFolder(folder);
                out.println("Added " + added.getPath());
                folders.app.catchUp(spec.commandLine().getErr());
                folders.app.printSummary(out);
                return 0;
            }
        }

        @Command(name = "remove", description = "Remove a search folder and its index entries.")
        static final class Remove implements Callable<Integer> {
            @ParentCommand Folders folders;
            @Spec CommandSpec spec;

            @Parameters(index = "0", paramLabel = "<folder>")
            Path folder;

            @Override
            public Integer call() throws Exception {
                folders.app.openIndex(spec.commandLine().getErr());
                folders.app.findAnythingService.removeSearchFolder(folder);
                folders.app.catchUp(spec.commandLine().getErr());
                spec.commandLine()
                        .getOut()
                        .println("Removed " + folder.toAbsolutePath().normalize());
                return 0;
            }
        }

        @Command(
                name = "pause",
                description = "Keep a folder's index entries but leave it out of searches.")
        static final class Pause implements Callable<Integer> {
            @ParentCommand Folders folders;
            @Spec CommandSpec spec;

            @Parameters(index = "0", paramLabel = "<folder>")
            Path folder;

            @Override
            public Integer call() throws Exception {
                folders.app.openIndex(spec.commandLine().getErr());
                folders.app.findAnythingService.setFolderPaused(folder, true);
                spec.commandLine()
                        .getOut()
                        .println("Paused " + folder.toAbsolutePath().normalize());
                return 0;
            }
        }

        @Command(name = "resume", description = "Search a paused folder again.")
        static final class Resume implements Callable<Integer> {
            @ParentCommand Folders folders;
            @Spec CommandSpec spec;

            @Parameters(index = "0", paramLabel = "<folder>")
            Path folder;

            @Override
            public Integer call() throws Exception {
                folders.app.openIndex(spec.commandLine().getErr());
                folders.app.findAnythingService.setFolderPaused(folder, false);
                spec.commandLine()
                        .getOut()
                        .println("Resumed " + folder.toAbsolutePath().normalize());
                return 0;
            }
        }

        @Command(name = "list", description = "List the search folders.")
        static final class ListFolders implements Callable<Integer> {
            @ParentCommand Folders folders;
            @Spec CommandSpec spec;

            @Override
            public Integer call() {
                PrintWriter out = spec.commandLine().getOut();
                List<SearchFolder> searchFolders =
                        folders.app.findAnythingService.getIndexConfig().getSearchFolders();
                if (searchFolders.isEmpty()) {
                    out.println(
                            "No search folders yet. Add one with: findany folders add <folder>");
                    return 0;
                }
                for (SearchFolder folder : searchFolders) {
                    StringBuilder line = new StringBuilder(folder.getPath().toString());
                    if (folder.isPaused()) {
                        line.append("  (paused)");
                    }
                    if (!folder.isAvailable()) {
                        line.append("  (unavailable)");
                    }
                    if (!folder.getExclusionRules().isEmpty()) {
                        line.append("  excludes: ")
                                .append(
                                        String.join(
                                                ", ",
                                                folder.getExclusionRules().stream()
                                                        .map(ExclusionRule::getPattern)
                                                        .toList()));
                    }
                    out.println(line);
                }
                return 0;
            }
        }
    }

    // ------------------------------------------------------------------ findany exclude

    /** {@code findany exclude add|remove|list} (F01, F14). */
    @Command(
            name = "exclude",
            description = "Manage exclusion patterns, globally or for one folder.",
            synopsisSubcommandLabel = "COMMAND",
            subcommands = {Exclude.Add.class, Exclude.Remove.class, Exclude.ListRules.class})
    static final class Exclude implements Callable<Integer> {
        @ParentCommand CliApp app;
        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(spec.commandLine().getOut());
            return 0;
        }

        @Command(
                name = "add",
                description = "Exclude files matching a pattern, such as *.tmp or node_modules.")
        static final class Add implements Callable<Integer> {
            @ParentCommand Exclude exclude;
            @Spec CommandSpec spec;

            @Parameters(index = "0", paramLabel = "<pattern>")
            String pattern;

            @Option(
                    names = "--folder",
                    paramLabel = "<folder>",
                    description = "Apply the rule to this search folder only.")
            Path folder;

            @Override
            public Integer call() throws Exception {
                exclude.app.openIndex(spec.commandLine().getErr());
                exclude.app.findAnythingService.addExclusion(pattern, folder);
                spec.commandLine()
                        .getOut()
                        .println(
                                "Excluding "
                                        + pattern
                                        + (folder == null
                                                ? " everywhere"
                                                : " in " + folder.toAbsolutePath().normalize()));
                exclude.app.catchUp(spec.commandLine().getErr());
                return 0;
            }
        }

        @Command(name = "remove", description = "Remove an exclusion pattern.")
        static final class Remove implements Callable<Integer> {
            @ParentCommand Exclude exclude;
            @Spec CommandSpec spec;

            @Parameters(index = "0", paramLabel = "<pattern>")
            String pattern;

            @Option(names = "--folder", paramLabel = "<folder>")
            Path folder;

            @Override
            public Integer call() throws Exception {
                exclude.app.openIndex(spec.commandLine().getErr());
                exclude.app.findAnythingService.removeExclusion(pattern, folder);
                spec.commandLine().getOut().println("No longer excluding " + pattern);
                exclude.app.catchUp(spec.commandLine().getErr());
                return 0;
            }
        }

        @Command(name = "list", description = "List the exclusion patterns.")
        static final class ListRules implements Callable<Integer> {
            @ParentCommand Exclude exclude;
            @Spec CommandSpec spec;

            @Option(
                    names = "--built-in",
                    description = "Also list the built-in exclusions, which always apply.")
            boolean builtIn;

            @Override
            public Integer call() {
                PrintWriter out = spec.commandLine().getOut();
                IndexConfig config = exclude.app.findAnythingService.getIndexConfig();
                out.println("Global: " + patterns(config.getGlobalRules()));
                for (SearchFolder folder : config.getSearchFolders()) {
                    out.println(folder.getPath() + ": " + patterns(folder.getExclusionRules()));
                }
                if (builtIn) {
                    out.println("Built-in (always apply): " + patterns(config.getBuiltInRules()));
                }
                return 0;
            }

            private static String patterns(List<ExclusionRule> rules) {
                return rules.isEmpty()
                        ? "(none)"
                        : String.join(", ", rules.stream().map(ExclusionRule::getPattern).toList());
            }
        }
    }

    // ------------------------------------------------------------------ findany index

    /** {@code findany index status|rebuild} (F02, F03). */
    @Command(
            name = "index",
            description = "Show the index's status or rebuild it.",
            synopsisSubcommandLabel = "COMMAND",
            subcommands = {Index.Status.class, Index.Rebuild.class})
    static final class Index implements Callable<Integer> {
        @ParentCommand CliApp app;
        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(spec.commandLine().getOut());
            return 0;
        }

        @Command(
                name = "status",
                description = "Catch up on changed files and show the index's status.")
        static final class Status implements Callable<Integer> {
            @ParentCommand Index index;
            @Spec CommandSpec spec;

            @Option(names = "--failed", description = "List the files that couldn't be extracted.")
            boolean failed;

            @Override
            public Integer call() throws Exception {
                PrintWriter out = spec.commandLine().getOut();
                IndexMode mode = index.app.openIndex(spec.commandLine().getErr());
                index.app.catchUp(spec.commandLine().getErr());
                IndexStatus status = index.app.findAnythingService.getIndexStatus();
                out.printf(
                        "Mode: %s%n",
                        mode == IndexMode.WRITER
                                ? "writer (this process updates the index)"
                                : "read-only");
                out.printf(
                        "State: %s%n",
                        status.state().name().toLowerCase(Locale.ROOT).replace('_', ' '));
                out.printf(
                        "Files: %d indexed, %d failed, %d pending%n",
                        index.app.findAnythingService.countIndexedFiles(),
                        status.failedFiles(),
                        status.pendingFiles());
                if (failed) {
                    List<IndexedFile> files = index.app.findAnythingService.listFailedFiles();
                    out.println(files.isEmpty() ? "No files failed." : "Failed files:");
                    files.forEach(file -> out.println("  " + file.path()));
                }
                return 0;
            }
        }

        @Command(
                name = "rebuild",
                description = "Drop the index and build it again from the search folders.")
        static final class Rebuild implements Callable<Integer> {
            @ParentCommand Index index;
            @Spec CommandSpec spec;

            @Override
            public Integer call() throws Exception {
                index.app.openIndex(spec.commandLine().getErr());
                index.app.findAnythingService.rebuildIndex();
                index.app.catchUp(spec.commandLine().getErr());
                index.app.printSummary(spec.commandLine().getOut());
                return 0;
            }
        }
    }

    // ------------------------------------------------------------------ findany search

    /** {@code findany search <query> [--limit N]} (F06). */
    @Command(
            name = "search",
            description =
                    "Find files by keywords; with no query, list every indexed file, newest first.")
    static final class Search implements Callable<Integer> {
        @ParentCommand CliApp app;
        @Spec CommandSpec spec;

        @Parameters(
                arity = "0..*",
                paramLabel = "<query>",
                description = "The words to search for.")
        List<String> words = List.of();

        @Option(
                names = {"-n", "--limit"},
                paramLabel = "<N>",
                description = "At most this many results (default ${DEFAULT-VALUE}).")
        int limit = 20;

        @Override
        public Integer call() throws Exception {
            PrintWriter out = spec.commandLine().getOut();
            app.openIndex(spec.commandLine().getErr());
            app.catchUp(spec.commandLine().getErr());
            String query = String.join(" ", words);
            List<SearchResult> results =
                    app.findAnythingService.search(query, SearchFilters.NONE, limit).join();
            if (results.isEmpty()) {
                out.println(
                        query.isBlank()
                                ? "The index is empty. Add a folder with: findany folders add <folder>"
                                : "No results.");
                return 0;
            }
            int number = 0;
            for (SearchResult result : results) {
                IndexedFile file = result.file();
                number++;
                out.printf("%d. %s%n", number, file.path());
                out.printf(
                        "   %s, %s, modified %s%s%n",
                        file.fileType().isEmpty() ? "no extension" : file.fileType(),
                        formatSize(file.sizeBytes()),
                        formatDate(file.modifiedTime()),
                        statusNote(file));
                if (result.bestChunk() != null) {
                    String unit = file.fileType().equals("pptx") ? "slide " : "page ";
                    String where =
                            result.bestChunk().pageNumber() > 0
                                    ? unit + result.bestChunk().pageNumber() + ": "
                                    : "";
                    out.println("   " + where + snippet(result.bestChunk().text(), 160));
                }
            }
            return 0;
        }

        private static String statusNote(IndexedFile file) {
            return switch (file.extractionStatus()) {
                case OK -> "";
                case PARTIAL -> " (partially indexed)";
                case METADATA_ONLY -> " (indexed by name only)";
                case NO_TEXT -> " (no extractable text)";
                case FAILED -> " (extraction failed)";
            };
        }
    }

    // ------------------------------------------------------------------ findany show

    /** {@code findany show <file> [--highlight <text>]} (F11). */
    @Command(
            name = "show",
            description = "Print a file's extracted text, with matches marked [[like this]].")
    static final class Show implements Callable<Integer> {
        @ParentCommand CliApp app;
        @Spec CommandSpec spec;

        @Parameters(index = "0", paramLabel = "<file>")
        Path file;

        @Option(
                names = "--highlight",
                paramLabel = "<text>",
                description = "Mark every occurrence of this text.")
        String highlight;

        @Override
        public Integer call() throws Exception {
            PrintWriter out = spec.commandLine().getOut();
            app.openIndex(spec.commandLine().getErr());
            app.catchUp(spec.commandLine().getErr());
            Preview preview = app.findAnythingService.preview(file, highlight).join();
            IndexedFile indexed = preview.file();
            if (preview.isMetadataOnly()) {
                out.println(indexed.path());
                out.printf(
                        "No extracted text (%s).%n",
                        Search.statusNote(indexed).replaceAll("^ \\(|\\)$", ""));
                out.printf(
                        "Type: %s%nSize: %s%nModified: %s%n",
                        indexed.fileType().isEmpty() ? "no extension" : indexed.fileType(),
                        formatSize(indexed.sizeBytes()),
                        formatDate(indexed.modifiedTime()));
                return 0;
            }
            out.println(markHighlights(preview));
            if (highlight != null && !highlight.isBlank()) {
                int count = preview.highlights().size();
                spec.commandLine()
                        .getErr()
                        .printf(
                                "%d match%s for \"%s\"%n",
                                count, count == 1 ? "" : "es", highlight);
            }
            return 0;
        }

        static String markHighlights(Preview preview) {
            String text = preview.text();
            StringBuilder marked = new StringBuilder();
            int from = 0;
            for (Preview.Range range : preview.highlights()) {
                if (range.start() < from) {
                    continue; // overlapping ranges: keep the earlier mark
                }
                marked.append(text, from, range.start())
                        .append("[[")
                        .append(text, range.start(), range.end())
                        .append("]]");
                from = range.end();
            }
            return marked.append(text.substring(from)).toString();
        }
    }
}
