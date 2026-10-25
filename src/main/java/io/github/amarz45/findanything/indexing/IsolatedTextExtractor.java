package io.github.amarz45.findanything.indexing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.apache.tika.config.TimeoutLimits;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.pipes.fork.PipesForkParser;
import org.apache.tika.pipes.fork.PipesForkParserConfig;
import org.apache.tika.pipes.fork.PipesForkResult;
import org.apache.tika.sax.BasicContentHandlerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Adapts Apache Tika's forked parser to {@link TextExtractor}. The file's type is detected in this
 * process from its contents; parsing happens in a child JVM with a time limit and a memory limit,
 * so a malformed file can't crash or freeze the application. Tika's types stay inside this class.
 *
 * <p>Tested behaviour and the reasons for each setting are in {@code docs/notes/tika/findings.md}.
 */
public final class IsolatedTextExtractor implements TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(IsolatedTextExtractor.class);

    private static final String PDF = "application/pdf";
    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    /** The formats text is extracted from; everything else is indexed by name only. */
    static final Set<String> SUPPORTED_TYPES =
            Set.of("text/plain", "text/markdown", "text/x-web-markdown", PDF, DOCX, PPTX);

    /** At most this many characters of text per file; beyond it the file is marked partial. */
    static final int WRITE_LIMIT_CHARS = 10_000_000;

    private final Duration timeout;
    private final long maxMemoryBytes;
    private final Detector detector;
    private final PipesForkParser parser;

    /**
     * Starts the extractor. The child process itself starts on the first {@link #extract} call
     * (about five seconds) and exits after a minute idle.
     *
     * @param appDataFolder where Tika's small configuration file is written
     * @param timeout how long a file may parse without progress before it fails
     * @param maxMemoryBytes the child process's maximum heap
     */
    public IsolatedTextExtractor(Path appDataFolder, Duration timeout, long maxMemoryBytes)
            throws IOException {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.maxMemoryBytes = maxMemoryBytes;
        warnIfPathsCannotBeNonAscii();
        Files.createDirectories(appDataFolder);
        Path tikaConfig = appDataFolder.resolve("tika-config.json");
        Files.writeString(tikaConfig, "{ \"text-recognizers\": [] }\n"); // OCR off on every machine
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        TimeoutLimits timeouts = new TimeoutLimits();
        timeouts.setProgressTimeoutMillis(timeout.toMillis());
        timeouts.setTotalTaskTimeoutMillis(
                Math.max(timeout.toMillis(), TimeoutLimits.DEFAULT_TOTAL_TASK_TIMEOUT_MILLIS));
        PipesForkParserConfig config =
                new PipesForkParserConfig()
                        .setJavaPath(java.toString()) // the app's own Java, not "java" from PATH
                        .setHandlerType(BasicContentHandlerFactory.HANDLER_TYPE.XML) // page divs
                        .setMaxEmbeddedCount(0) // skip thumbnails and files embedded in documents
                        .setWriteLimit(WRITE_LIMIT_CHARS)
                        .setUserConfigPath(tikaConfig)
                        .setTimeoutLimits(timeouts)
                        .addJvmArg("-Xmx" + Math.max(1, maxMemoryBytes / (1024 * 1024)) + "m")
                        .addJvmArg("-Dlog4j2.configurationFile=classpath:tika-fork-log4j2.xml");
        this.detector = new DefaultDetector();
        try {
            this.parser = new PipesForkParser(config); // owns the child process
        } catch (TikaConfigException e) {
            throw new IOException("Could not configure the text extractor: " + e.getMessage(), e);
        }
    }

    private static void warnIfPathsCannotBeNonAscii() {
        String encoding = System.getProperty("sun.jnu.encoding", "");
        if (!encoding.equalsIgnoreCase("UTF-8")) {
            log.warn(
                    "The system's file-name encoding is {} rather than UTF-8; files with non-ASCII"
                            + " names can't be extracted. Start the app with a UTF-8 locale.",
                    encoding);
        }
    }

    @Override
    public ExtractedDocument extract(Path file)
            throws UnsupportedFormatException, IOException, ExtractionException {
        String type = detectType(file);
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new UnsupportedFormatException(type);
        }
        PipesForkResult result;
        try {
            result = parser.parse(file);
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractionException("extraction was interrupted", e);
        } catch (Exception e) { // PipesException, TikaException
            throw new ExtractionException("the parser failed: " + e.getMessage(), e);
        }
        if (result.isProcessCrash()) { // OOM, TIMEOUT, UNSPECIFIED_CRASH; the child restarts itself
            throw new ExtractionException("the parser " + describeCrash(result));
        }
        if (!result.isSuccess()) { // for example FETCH_EXCEPTION: the file vanished or is locked
            throw new IOException("could not read the file: " + result.getStatus());
        }
        // isSuccess() is also true when Tika could NOT read the file
        // (PARSE_SUCCESS_WITH_EXCEPTION).
        Metadata metadata = result.getMetadataList().get(0);
        String xhtml = metadata.get(TikaCoreProperties.TIKA_CONTENT);
        String error = metadata.get(TikaCoreProperties.CONTAINER_EXCEPTION);
        if (xhtml == null && "true".equals(metadata.get(TikaCoreProperties.IS_ENCRYPTED))) {
            throw new ExtractionException("the file is password-protected");
        }
        if (xhtml == null || error != null) {
            throw new ExtractionException(
                    "the file could not be read: "
                            + (error == null
                                    ? "no content"
                                    : error.lines().findFirst().orElse("")));
        }
        boolean truncated = "true".equals(metadata.get(TikaCoreProperties.WRITE_LIMIT_REACHED));
        List<String> pages = PageSplitter.split(xhtml, truncated);
        boolean numberedPages = type.equals(PDF) || type.equals(PPTX);
        if (type.equals(PPTX) && !truncated) {
            pages = SlideOrder.fix(file, pages);
        }
        if (!numberedPages) {
            pages = List.of(String.join("\n", pages));
        }
        return new ExtractedDocument(type, pages, numberedPages, truncated);
    }

    private String detectType(Path file) throws IOException {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());
        try (TikaInputStream in = TikaInputStream.get(file, metadata)) {
            MediaType mediaType = detector.detect(in, metadata, new ParseContext());
            return mediaType.getBaseType().toString();
        }
    }

    private String describeCrash(PipesForkResult result) {
        return switch (result.getStatus()) {
            case TIMEOUT -> "took longer than " + timeout.toSeconds() + " seconds";
            case OOM -> "ran out of memory (limit " + maxMemoryBytes / (1024 * 1024) + " MB)";
            default -> "crashed (" + result.getStatus() + ")";
        };
    }

    @Override
    public void close() throws IOException {
        parser.close(); // stops the child process
    }

    // ------------------------------------------------------------------ XHTML -> pages

    /**
     * Splits Tika's XHTML at {@code <div class="page">} (PDF) and {@code <div
     * class="slide-content">} (PPTX).
     */
    private static final class PageSplitter extends DefaultHandler {
        private final List<StringBuilder> pages = new ArrayList<>();
        private StringBuilder current;
        private boolean inBody;

        static List<String> split(String xhtml, boolean truncated) throws ExtractionException {
            PageSplitter handler = new PageSplitter();
            try {
                secureSaxFactory(false)
                        .newSAXParser()
                        .parse(new InputSource(new StringReader(xhtml)), handler);
            } catch (SAXException | IOException | ParserConfigurationException e) {
                // When the write limit is hit, Tika's XHTML stops mid-document; keep what was read.
                if (!truncated) {
                    throw new ExtractionException(
                            "bad XHTML from the parser: " + e.getMessage(), e);
                }
            }
            List<String> out = new ArrayList<>();
            for (StringBuilder page : handler.pages) {
                out.add(page.toString().replaceAll("[ \\t]*\\n\\s*", "\n").strip());
            }
            return out.isEmpty() ? List.of("") : out;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            if (qName.equals("body")) {
                inBody = true;
            }
            if (!inBody) {
                return;
            }
            String cls = atts.getValue("class");
            if (qName.equals("div") && ("page".equals(cls) || "slide-content".equals(cls))) {
                current = new StringBuilder();
                pages.add(current);
            } else if (qName.matches("p|h[1-6]|li|tr|td|br|div")) {
                append("\n");
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (qName.equals("body")) {
                inBody = false;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inBody) {
                append(new String(ch, start, length));
            }
        }

        // Text after a slide's div (its speaker notes) stays with that slide.
        // A document with no page divs at all (DOCX, TXT, MD) becomes one page.
        private void append(String s) {
            if (current == null) {
                if (s.isBlank()) {
                    return;
                }
                current = new StringBuilder();
                pages.add(current);
            }
            current.append(s);
        }
    }

    // ------------------------------------------------------------------ PPTX slide order

    /**
     * Tika 4.1.0 emits PPTX slides sorted by relationship id as text ("rId10" before "rId7"), not
     * in the deck's real order, which is the order of {@code <p:sldIdLst>} in {@code
     * ppt/presentation.xml}. This puts them back in deck order.
     */
    private static final class SlideOrder {
        private static final String REL_NS =
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

        static List<String> fix(Path pptx, List<String> tikaPages) throws IOException {
            Map<String, String> slideRels = new TreeMap<>(); // rId -> target, sorted like Tika
            List<String> realOrder = new ArrayList<>(); // rIds in the deck's order
            try (ZipFile zip = new ZipFile(pptx.toFile())) {
                readXml(
                        zip,
                        "ppt/_rels/presentation.xml.rels",
                        (name, a) -> {
                            String type = a.getValue("Type");
                            if (name.equals("Relationship")
                                    && type != null
                                    && type.endsWith("/relationships/slide")) {
                                slideRels.put(a.getValue("Id"), a.getValue("Target"));
                            }
                        });
                readXml(
                        zip,
                        "ppt/presentation.xml",
                        (name, a) -> {
                            if (name.equals("sldId")) {
                                realOrder.add(a.getValue(REL_NS, "id"));
                            }
                        });
            }
            List<String> tikaOrder = new ArrayList<>(slideRels.keySet());
            if (tikaOrder.size() != tikaPages.size() || !realOrder.containsAll(tikaOrder)) {
                return tikaPages; // unexpected file layout: keep Tika's order rather than guess
            }
            String[] fixed = new String[tikaPages.size()];
            for (int k = 0; k < tikaPages.size(); k++) {
                fixed[realOrder.indexOf(tikaOrder.get(k))] = tikaPages.get(k);
            }
            return List.of(fixed);
        }

        private static void readXml(
                ZipFile zip, String entryName, BiConsumer<String, Attributes> onElement)
                throws IOException {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException("missing " + entryName);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] xml =
                        in.readNBytes(5_000_000); // tiny parts; the cap guards against zip bombs
                secureSaxFactory(true)
                        .newSAXParser()
                        .parse(
                                new ByteArrayInputStream(xml),
                                new DefaultHandler() {
                                    @Override
                                    public void startElement(
                                            String uri,
                                            String localName,
                                            String qName,
                                            Attributes atts) {
                                        onElement.accept(localName, atts);
                                    }
                                });
            } catch (SAXException | ParserConfigurationException e) {
                throw new IOException("bad " + entryName + ": " + e.getMessage(), e);
            }
        }
    }

    private static SAXParserFactory secureSaxFactory(boolean namespaceAware)
            throws ParserConfigurationException, SAXException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory;
    }
}
