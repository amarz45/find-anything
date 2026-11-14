package io.github.amarz45.findanything.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.amarz45.findanything.agent.Answer;
import io.github.amarz45.findanything.agent.Citation;
import io.github.amarz45.findanything.agent.Conversation;
import io.github.amarz45.findanything.agent.ConversationStore;
import io.github.amarz45.findanything.agent.VerificationStatus;
import io.github.amarz45.findanything.config.BackendType;
import io.github.amarz45.findanything.config.EnvFile;
import io.github.amarz45.findanything.config.ExclusionRule;
import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.config.ModelSettings;
import io.github.amarz45.findanything.config.SearchFolder;
import io.github.amarz45.findanything.config.SettingsStore;
import io.github.amarz45.findanything.core.FindAnythingService;
import io.github.amarz45.findanything.core.Preview;
import io.github.amarz45.findanything.indexing.ChangeQueue;
import io.github.amarz45.findanything.indexing.Chunker;
import io.github.amarz45.findanything.indexing.IndexStatus;
import io.github.amarz45.findanything.indexing.IsolatedTextExtractor;
import io.github.amarz45.findanything.indexing.TextExtractor;
import io.github.amarz45.findanything.llm.LlmClientFactory;
import io.github.amarz45.findanything.llm.Message;
import io.github.amarz45.findanything.llm.RedactingLlmClient;
import io.github.amarz45.findanything.llm.ToolCall;
import io.github.amarz45.findanything.privacy.FolderPolicy;
import io.github.amarz45.findanything.privacy.PrivacyLog;
import io.github.amarz45.findanything.privacy.PrivacyLogEntry;
import io.github.amarz45.findanything.privacy.Redactor;
import io.github.amarz45.findanything.privacy.SensitivePattern;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.bridge.SLF4JBridgeHandler;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * The {@code findany} command line. With no arguments it prints help; every subcommand that uses
 * the index first catches up on files changed since the last run (F03), then does its work through
 * {@link FindAnythingService} and waits for the result. {@code main} is the composition root: it
 * resolves the data folder ({@code FINDANY_HOME}), reads {@code .env}, and builds the service and
 * its collaborators.
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
            CliApp.Ask.class,
            CliApp.Show.class,
            CliApp.Model.class,
            CliApp.Privacy.class,
            CliApp.Policy.class,
            CliApp.PrivacyLogCommand.class,
            CliApp.Gui.class
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
        try (FindAnythingService service = createService(home, loadEnvironment(home))) {
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
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
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

    /**
     * The API key and model defaults: {@code .env} in the data folder, overridden by {@code .env}
     * in the working directory (the one {@code .env.example} is copied to), overridden by the
     * environment variables.
     */
    static Map<String, String> loadEnvironment(Path home) throws IOException {
        return EnvFile.load(System.getenv(), home.resolve(".env"), Path.of(".env"));
    }

    /** Builds the facade and everything behind it for one CLI run. */
    static FindAnythingService createService(Path home, Map<String, String> environment)
            throws IOException {
        SettingsStore settingsStore = new SettingsStore(home.resolve("settings.json"), environment);
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
        // The privacy objects are made once and shared: the factory puts them around every cloud
        // client, and the facade changes them when the user changes a policy.
        FolderPolicy folderPolicy = settingsStore.loadFolderPolicy();
        Redactor redactor = loadRedactor(settingsStore);
        PrivacyLog privacyLog = new PrivacyLog(home.resolve("privacy-log.jsonl"));
        Clock clock = Clock.systemDefaultZone();
        return new FindAnythingService(
                indexConfig,
                settingsStore,
                new LlmClientFactory(redactor, folderPolicy, privacyLog, clock),
                folderPolicy,
                redactor,
                privacyLog,
                new ConversationStore(home.resolve("conversations"), settingsStore),
                textExtractor,
                new Chunker(1_500),
                changeQueue,
                home.resolve("index"),
                backgroundExecutor,
                clock);
    }

    /** The redactor with every built-in pattern, minus the categories the user turned off. */
    static Redactor loadRedactor(SettingsStore settingsStore) throws IOException {
        Redactor redactor = new Redactor(SensitivePattern.builtIn());
        for (String category : settingsStore.loadDisabledRedactionCategories()) {
            try {
                redactor.setCategoryEnabled(category, false);
            } catch (IllegalArgumentException e) {
                throw new IOException(
                        "The settings file "
                                + settingsStore.getSettingsFile()
                                + " turns off a redaction category that doesn't exist. "
                                + e.getMessage());
            }
        }
        return redactor;
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

    /** A failure as the user should read it; the GUI's error bar uses the same words. */
    static String describe(Throwable e) {
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

    /** A size as the user writes it: a number, then B, KB, MB, GB or TB (bytes when left out). */
    private static final Pattern SIZE =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([KMGT]?B)?", Pattern.CASE_INSENSITIVE);

    /**
     * Reads a size filter such as "500KB", "1.5 mb" or "2GB" (F04) as bytes, with the units {@link
     * #formatSize} writes (1 KB is 1024 bytes).
     *
     * @throws IllegalArgumentException with the message to show when the text isn't a size
     */
    static long parseSize(String text) {
        Matcher matcher = SIZE.matcher(text.strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "The size \"" + text + "\" isn't understood. Write it like 500KB, 1MB or 2GB.");
        }
        String unit = matcher.group(2) == null ? "B" : matcher.group(2).toUpperCase(Locale.ROOT);
        long bytesPerUnit = 1L << (10 * "BKMGT".indexOf(unit.charAt(0)));
        return Math.round(Double.parseDouble(matcher.group(1)) * bytesPerUnit);
    }

    static String formatDate(Instant instant) {
        return DATE.format(instant.atZone(ZoneId.systemDefault()));
    }

    static String snippet(String text, int maxChars) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= maxChars ? oneLine : oneLine.substring(0, maxChars - 1) + "…";
    }

    // ------------------------------------------------------------------ findany privacy, policy

    /**
     * {@code findany privacy} shows the privacy settings; {@code privacy redact} changes one (F14).
     */
    @Command(
            name = "privacy",
            description = "Show what is kept from cloud models, or change the redaction.",
            synopsisSubcommandLabel = "[COMMAND]",
            subcommands = {Privacy.Redact.class})
    static final class Privacy implements Callable<Integer> {
        @ParentCommand CliApp app;
        @Spec CommandSpec spec;

        /** The limit the design says must be stated plainly. */
        static final String LIMITATION =
                "Only data with a recognizable shape is found: card numbers, SINs, private keys and"
                        + " API keys. Other sensitive text, such as a password in a note, is not"
                        + " detected; mark its folder local model only, or exclude the file.";

        @Override
        public Integer call() {
            PrintWriter out = spec.commandLine().getOut();
            FindAnythingService service = app.findAnythingService;
            out.println("Redaction, applied to every request to a cloud model:");
            service.getRedactionCategories()
                    .forEach(
                            (category, on) ->
                                    out.printf("  %-12s %s%n", category, on ? "on" : "off"));
            out.println(LIMITATION);
            out.println();
            Set<Path> folders = service.getLocalOnlyFolders();
            if (folders.isEmpty()) {
                out.println(
                        "No folder is marked local model only. Mark one with: findany policy"
                                + " local-only <folder>");
            } else {
                out.println("Local model only (their text is withheld from cloud models):");
                folders.stream().sorted().forEach(folder -> out.println("  " + folder));
            }
            out.println("Privacy log: " + service.getPrivacyLogFile());
            return 0;
        }

        @Command(name = "redact", description = "Turn the redaction of one category on or off.")
        static final class Redact implements Callable<Integer> {
            @ParentCommand Privacy privacy;
            @Spec CommandSpec spec;

            static final class Choice {
                @Option(names = "--enable", paramLabel = "<category>", description = "Redact it.")
                String enable;

                @Option(
                        names = "--disable",
                        paramLabel = "<category>",
                        description = "Send it as it is.")
                String disable;
            }

            @ArgGroup(exclusive = true, multiplicity = "1")
            Choice choice;

            @Override
            public Integer call() throws Exception {
                boolean enabled = choice.enable != null;
                String category = enabled ? choice.enable : choice.disable;
                privacy.app.openIndex(spec.commandLine().getErr());
                privacy.app.findAnythingService.setRedaction(category, enabled);
                spec.commandLine()
                        .getOut()
                        .println(
                                "Redaction of "
                                        + category
                                        + " is "
                                        + (enabled ? "on" : "off")
                                        + " for the next requests to a cloud model.");
                return 0;
            }
        }
    }

    /** {@code findany policy local-only <folder> [--off]} (F14). */
    @Command(
            name = "policy",
            description = "Folder policies for cloud models.",
            synopsisSubcommandLabel = "COMMAND",
            subcommands = {Policy.LocalOnly.class})
    static final class Policy implements Callable<Integer> {
        @ParentCommand CliApp app;
        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(spec.commandLine().getOut());
            return 0;
        }

        @Command(
                name = "local-only",
                description =
                        "Mark a folder \"local model only\": its files' text is withheld from every"
                                + " request to a cloud model.")
        static final class LocalOnly implements Callable<Integer> {
            @ParentCommand Policy policy;
            @Spec CommandSpec spec;

            @Parameters(
                    index = "0",
                    paramLabel = "<folder>",
                    description = "A search folder, or a folder inside one.")
            Path folder;

            @Option(names = "--off", description = "Remove the mark instead.")
            boolean off;

            @Override
            public Integer call() throws Exception {
                policy.app.openIndex(spec.commandLine().getErr());
                policy.app.findAnythingService.setLocalOnly(folder, !off);
                Path shown = folder.toAbsolutePath().normalize();
                spec.commandLine()
                        .getOut()
                        .println(
                                off
                                        ? "Removed the local-only mark from " + shown + "."
                                        : "Marked "
                                                + shown
                                                + " local model only: its text is withheld from"
                                                + " cloud models from the next request on.");
                return 0;
            }
        }
    }

    // ------------------------------------------------------------------ findany privacy-log

    /** {@code findany privacy-log [--last N]}: what was sent to cloud models (F13, UC09). */
    @Command(
            name = "privacy-log",
            description = "Show the requests sent to cloud models, newest first.")
    static final class PrivacyLogCommand implements Callable<Integer> {
        @ParentCommand CliApp app;
        @Spec CommandSpec spec;

        static final int EXCERPT_CHARS = 200;

        @Option(
                names = "--last",
                paramLabel = "<n>",
                description = "How many requests to show (default: ${DEFAULT-VALUE}).")
        int last = 10;

        @Override
        public Integer call() throws Exception {
            if (last < 1) {
                throw new CommandLine.ParameterException(
                        spec.commandLine(), "--last must be at least 1.");
            }
            PrintWriter out = spec.commandLine().getOut();
            FindAnythingService service = app.findAnythingService;
            List<PrivacyLogEntry> entries = service.getPrivacyLog(last);
            if (entries.isEmpty()) {
                out.println("Nothing has been sent to a cloud model.");
            }
            int number = 0;
            for (PrivacyLogEntry entry : entries) {
                number++;
                out.print(describe(number, entry));
            }
            out.println("Full text: " + service.getPrivacyLogFile());
            return 0;
        }

        /**
         * One entry as the user reads it: when, the model, how much, what was redacted and
         * withheld, then each message's start with its source files. The settings dialog shows the
         * same text.
         */
        static String describe(int number, PrivacyLogEntry entry) {
            StringBuilder text = new StringBuilder();
            int characters =
                    entry.excerpts().stream().mapToInt(excerpt -> excerpt.text().length()).sum();
            text.append(
                    String.format(
                            Locale.ROOT,
                            "%d. %s  %s  %d message%s, %,d characters%n",
                            number,
                            formatDate(entry.timestamp()),
                            entry.modelName(),
                            entry.excerpts().size(),
                            entry.excerpts().size() == 1 ? "" : "s",
                            characters));
            text.append("   Redacted: ")
                    .append(
                            entry.redactedCategories().isEmpty()
                                    ? "nothing"
                                    : String.join(", ", entry.redactedCategories()))
                    .append('\n');
            List<String> withheld =
                    entry.excerpts().stream()
                            .filter(excerpt -> excerpt.text().equals(RedactingLlmClient.WITHHELD))
                            .flatMap(excerpt -> excerpt.sourceFiles().stream())
                            .map(path -> String.valueOf(path.getFileName()))
                            .toList();
            if (!withheld.isEmpty()) {
                text.append("   Withheld: ").append(String.join(", ", withheld)).append('\n');
            }
            for (PrivacyLogEntry.Excerpt excerpt : entry.excerpts()) {
                String files =
                        excerpt.sourceFiles().isEmpty()
                                ? ""
                                : " ("
                                        + String.join(
                                                ", ",
                                                excerpt.sourceFiles().stream()
                                                        .map(
                                                                path ->
                                                                        String.valueOf(
                                                                                path.getFileName()))
                                                        .toList())
                                        + ")";
                text.append("   [")
                        .append(excerpt.role())
                        .append(']')
                        .append(files)
                        .append(' ')
                        .append(snippet(excerpt.text(), EXCERPT_CHARS))
                        .append('\n');
            }
            return text.toString();
        }
    }

    // ------------------------------------------------------------------ findany gui

    /**
     * {@code findany gui}: opens the window and returns when it is closed. The index is opened
     * first, like every other command; the GUI shows indexing progress instead of waiting for it.
     * JavaFX is started with {@code Platform.startup()} rather than {@code Application.launch()},
     * which would create the application object itself and leave no way to hand it the service.
     */
    @Command(name = "gui", description = "Open the graphical interface.")
    static final class Gui implements Callable<Integer> {
        @ParentCommand CliApp app;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            PrintWriter err = spec.commandLine().getErr();
            app.openIndex(err);
            CountDownLatch closed = new CountDownLatch(1);
            AtomicBoolean failed = new AtomicBoolean();
            try {
                Platform.startup(
                        () -> {
                            try {
                                MainWindow window = new MainWindow();
                                new GuiController(app.findAnythingService, window);
                                Stage stage = new Stage();
                                stage.setOnHidden(event -> closed.countDown());
                                window.show(stage);
                            } catch (RuntimeException e) {
                                err.println("findany: could not open the window: " + describe(e));
                                failed.set(true);
                                closed.countDown();
                            }
                        });
            } catch (RuntimeException e) {
                // No display, or the toolkit can't start here.
                err.println("findany: could not start the graphical interface: " + describe(e));
                return FAILURE;
            }
            closed.await();
            Platform.exit();
            return failed.get() ? FAILURE : 0;
        }
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
                Set<Path> localOnly = folders.app.findAnythingService.getLocalOnlyFolders();
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
                    line.append(localOnlyNote(folder.getPath(), localOnly));
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

            /** "(local model only)" for a marked folder, or the marked folders inside it. */
            private static String localOnlyNote(Path folder, Set<Path> localOnly) {
                if (localOnly.contains(folder)) {
                    return "  (local model only)";
                }
                List<String> inside =
                        localOnly.stream()
                                .filter(marked -> marked.startsWith(folder))
                                .map(marked -> folder.relativize(marked).toString())
                                .sorted()
                                .toList();
                return inside.isEmpty()
                        ? ""
                        : "  (local model only: " + String.join(", ", inside) + ")";
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

    /** {@code findany search <query> [filters] [--limit N]} (F06, with the filters of F04). */
    @Command(
            name = "search",
            description =
                    "Find files by keywords; with no query, list every indexed file, newest first."
                            + " The filters narrow either by type, date, size and folder.")
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

        @Option(
                names = "--type",
                split = ",",
                paramLabel = "<type>",
                description = "Only files of these types, such as pdf,docx.")
        List<String> types = List.of();

        @Option(
                names = "--after",
                paramLabel = "<date>",
                description = "Only files modified on or after this day, such as 2025-01-01.")
        String after;

        @Option(
                names = "--before",
                paramLabel = "<date>",
                description = "Only files modified on or before this day, such as 2025-12-31.")
        String before;

        @Option(
                names = "--min-size",
                paramLabel = "<size>",
                description = "Only files at least this big, such as 1MB.")
        String minSize;

        @Option(
                names = "--max-size",
                paramLabel = "<size>",
                description = "Only files at most this big, such as 50MB.")
        String maxSize;

        @Option(
                names = "--in",
                paramLabel = "<folder>",
                description = "Only files under this folder, which must be in a search folder.")
        Path location;

        @Override
        public Integer call() throws Exception {
            PrintWriter out = spec.commandLine().getOut();
            SearchFilters searchFilters = searchFilters();
            app.openIndex(spec.commandLine().getErr());
            app.catchUp(spec.commandLine().getErr());
            String query = String.join(" ", words);
            List<SearchResult> results =
                    app.findAnythingService.search(query, searchFilters, limit).join();
            if (results.isEmpty()) {
                if (!searchFilters.isEmpty()) {
                    out.println("No results. Try loosening the filters.");
                } else if (query.isBlank()) {
                    out.println(
                            "The index is empty. Add a folder with: findany folders add <folder>");
                } else {
                    out.println("No results.");
                }
                return 0;
            }
            printResults(out, results);
            return 0;
        }

        /**
         * The filters the options give (F04). Whether they make sense together is checked by the
         * search itself; a date or size that can't be read is a usage error here.
         */
        private SearchFilters searchFilters() {
            List<String> fileTypes =
                    types.stream()
                            .map(type -> type.strip().toLowerCase(Locale.ROOT))
                            .map(type -> type.startsWith(".") ? type.substring(1) : type)
                            .filter(type -> !type.isEmpty())
                            .toList();
            return new SearchFilters(
                    Set.copyOf(fileTypes),
                    date(after),
                    date(before),
                    size(minSize),
                    size(maxSize),
                    location);
        }

        private LocalDate date(String text) {
            if (text == null) {
                return null;
            }
            try {
                return LocalDate.parse(text.strip());
            } catch (DateTimeParseException e) {
                throw new CommandLine.ParameterException(
                        spec.commandLine(),
                        "The date \"" + text + "\" isn't understood. Write it like 2025-01-31.");
            }
        }

        private long size(String text) {
            if (text == null) {
                return -1;
            }
            try {
                return parseSize(text);
            } catch (IllegalArgumentException e) {
                throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
            }
        }

        /** Prints a numbered list of files, each with its details and its best passage, if any. */
        static void printResults(PrintWriter out, List<SearchResult> results) {
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

    // ------------------------------------------------------------------ findany ask

    /** {@code findany ask [--continue] [--verbose] [--json] <request>} (F07, F08, F09, F12). */
    @Command(
            name = "ask",
            description = "Ask the AI agent to find files or to answer a question from them.")
    static final class Ask implements Callable<Integer> {
        @ParentCommand CliApp app;
        @Spec CommandSpec spec;

        @Parameters(
                arity = "1..*",
                paramLabel = "<request>",
                description = "What to find or answer, in your own words.")
        List<String> words;

        @Option(
                names = "--continue",
                description =
                        "Continue the most recent conversation, so the request can build on its"
                                + " earlier answers. Otherwise a new conversation starts.")
        boolean continueLast;

        @Option(
                names = "--verbose",
                description = "Also print each step of the agent, on standard error.")
        boolean verbose;

        @Option(
                names = "--json",
                description =
                        "Print the result as JSON: the answer, each citation's status, the tool"
                                + " calls, the model, and whether instant search stood in for an"
                                + " unavailable model.")
        boolean json;

        @Override
        public Integer call() throws Exception {
            PrintWriter out = spec.commandLine().getOut();
            PrintWriter err = spec.commandLine().getErr();
            app.openIndex(err);
            app.catchUp(err);
            FindAnythingService service = app.findAnythingService;
            Conversation conversation;
            if (continueLast) {
                List<Conversation> saved = service.listConversations();
                if (saved.isEmpty()) {
                    throw new IllegalStateException(
                            "There is no conversation to continue. Ask once without --continue.");
                }
                conversation = saved.get(0);
            } else {
                conversation = service.newConversation();
            }
            String request = String.join(" ", words);
            Consumer<String> progressListener = verbose ? err::println : step -> {};
            Answer answer = service.ask(request, conversation, progressListener).join();
            if (json) {
                out.println(toJson(request, conversation, answer));
            } else {
                printAnswer(out, answer);
            }
            return 0;
        }

        /**
         * Prints the answer, then its sources, each marked {@code [✓]} when its quote was found in
         * the file and {@code [?]} when it wasn't. The status is also written out in words, since a
         * console that can't show the check mark prints a question mark in its place.
         */
        static void printAnswer(PrintWriter out, Answer answer) {
            out.println(answer.text());
            List<Citation> citations = answer.citations();
            if (!citations.isEmpty()) {
                out.println();
                out.println("Sources:");
                int number = 0;
                int unverified = 0;
                for (Citation citation : citations) {
                    number++;
                    boolean verified = citation.status() == VerificationStatus.VERIFIED;
                    unverified += verified ? 0 : 1;
                    out.printf(
                            "[%d] %s %s%s (%s)%n",
                            number,
                            verified ? "[✓]" : "[?]",
                            citation.file(),
                            place(citation),
                            describe(citation.status()));
                    if (!citation.quote().isBlank()) {
                        out.println("    \"" + snippet(citation.quote(), 300) + "\"");
                    }
                }
                out.println();
                if (unverified == 0) {
                    out.println(
                            citations.size() == 1
                                    ? "The citation was found in its file."
                                    : "All "
                                            + citations.size()
                                            + " citations were found in their files.");
                } else {
                    out.printf(
                            "%d of %d citation%s could not be verified.%n",
                            unverified, citations.size(), citations.size() == 1 ? "" : "s");
                }
            } else if (!answer.sourceFiles().isEmpty()) {
                out.println();
                out.println("Files:");
                Search.printResults(out, answer.sourceFiles());
            }
            for (String notice : answer.notices()) {
                out.println();
                out.println("Note: " + notice);
            }
        }

        /** ", page 2" or ", slide 2" for a citation with a page, otherwise "". */
        static String place(Citation citation) {
            if (citation.pageNumber() == 0) {
                return "";
            }
            String unit = IndexedFile.fileTypeOf(citation.file()).equals("pptx") ? "slide" : "page";
            return ", " + unit + " " + citation.pageNumber();
        }

        /** A citation's verification status in words. */
        static String describe(VerificationStatus status) {
            return switch (status) {
                case VERIFIED -> "verified";
                case UNVERIFIED -> "unverified: the quote was not found there";
                case SOURCE_CHANGED -> "unverified: the file has changed since it was indexed";
            };
        }

        /**
         * The result as JSON, for scripts and behaviour tests. {@code toolCalls} are the calls the
         * model made for this request, in order, including any that were rejected.
         */
        static String toJson(String request, Conversation conversation, Answer answer)
                throws JsonProcessingException {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("conversation", conversation.getId());
            ModelSettings settings = conversation.getModelSettings();
            ObjectNode model = root.putObject("model");
            model.put("backend", settings.backendType().name().toLowerCase(Locale.ROOT));
            model.put("name", settings.modelName());
            root.put("request", request);
            root.put("answer", answer.text());
            root.put("verified", answer.isVerified());
            root.put(
                    "fallback",
                    answer.notices().stream()
                            .anyMatch(n -> n.startsWith(FindAnythingService.MODEL_UNAVAILABLE)));
            ArrayNode citations = root.putArray("citations");
            int number = 0;
            for (Citation citation : answer.citations()) {
                ObjectNode node = citations.addObject();
                node.put("number", ++number);
                node.put("file", citation.file().toString());
                if (citation.pageNumber() > 0) {
                    node.put("page", citation.pageNumber());
                } else {
                    node.putNull("page");
                }
                node.put("quote", citation.quote());
                node.put("status", citation.status().name().toLowerCase(Locale.ROOT));
            }
            ArrayNode sourceFiles = root.putArray("sourceFiles");
            answer.sourceFiles()
                    .forEach(result -> sourceFiles.add(result.file().path().toString()));
            ArrayNode notices = root.putArray("notices");
            answer.notices().forEach(notices::add);
            ArrayNode toolCalls = root.putArray("toolCalls");
            List<Message> messages = conversation.getMessages();
            int start = messages.size();
            while (start > 0 && messages.get(start - 1).role() != Message.Role.USER) {
                start--;
            }
            for (Message message : messages.subList(start, messages.size())) {
                for (ToolCall toolCall : message.toolCalls()) {
                    ObjectNode node = toolCalls.addObject();
                    node.put("name", toolCall.name());
                    node.set("arguments", mapper.valueToTree(toolCall.arguments()));
                }
            }
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
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

    // ------------------------------------------------------------------ findany model

    /** {@code findany model set cloud|local [--model <name>]} and {@code model test} (F13). */
    @Command(
            name = "model",
            description = "Choose and test the model that answers questions.",
            synopsisSubcommandLabel = "COMMAND",
            subcommands = {Model.Set.class, Model.Test.class})
    static final class Model implements Callable<Integer> {
        @ParentCommand CliApp app;
        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(spec.commandLine().getOut());
            return 0;
        }

        /** How the settings are described to the user; never includes the key. */
        static String describe(ModelSettings settings) {
            return switch (settings.backendType()) {
                case CLOUD -> "cloud model " + settings.modelName() + " through OpenRouter";
                case LOCAL ->
                        "local model "
                                + settings.modelName()
                                + " through Ollama at "
                                + settings.baseUrl();
            };
        }

        /** Tests the backend and reports; true when it answered. */
        static boolean test(CliApp app, ModelSettings settings, PrintWriter out) {
            out.println("Testing the " + describe(settings) + "...");
            if (settings.backendType() == BackendType.CLOUD) {
                out.println(
                        "API key: "
                                + (settings.hasApiKey()
                                        ? "set (from .env or the environment)"
                                        : "none"));
            }
            boolean answered = app.findAnythingService.testBackend(settings).join();
            if (answered) {
                out.println("Connection OK.");
            } else {
                out.println("The backend was reached but answered nothing.");
            }
            return answered;
        }

        @Command(
                name = "set",
                description = "Test a backend and, if it answers, use it for new conversations.")
        static final class Set implements Callable<Integer> {
            @ParentCommand Model model;
            @Spec CommandSpec spec;

            @Parameters(
                    index = "0",
                    paramLabel = "<backend>",
                    description = "cloud (OpenRouter) or local (Ollama).")
            BackendType backendType;

            @Option(
                    names = "--model",
                    paramLabel = "<name>",
                    description =
                            "The model: an OpenRouter model ID or an Ollama model name. Otherwise"
                                    + " the saved one, or OPENROUTER_MODEL / OLLAMA_MODEL from .env.")
            String modelName;

            @Override
            public Integer call() throws Exception {
                PrintWriter out = spec.commandLine().getOut();
                ModelSettings settings =
                        model.app.findAnythingService.resolveModelSettings(backendType, modelName);
                if (!test(model.app, settings, out)) {
                    spec.commandLine().getErr().println("Nothing saved.");
                    return FAILURE;
                }
                model.app.findAnythingService.setBackend(settings);
                out.println("Saved. New conversations use the " + describe(settings) + ".");
                return 0;
            }
        }

        @Command(name = "test", description = "Test the connection to the current backend.")
        static final class Test implements Callable<Integer> {
            @ParentCommand Model model;
            @Spec CommandSpec spec;

            @Override
            public Integer call() throws Exception {
                ModelSettings settings = model.app.findAnythingService.getModelSettings();
                return test(model.app, settings, spec.commandLine().getOut()) ? 0 : FAILURE;
            }
        }
    }
}
