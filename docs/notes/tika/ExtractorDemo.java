import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;

import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.pipes.fork.PipesForkParser;
import org.apache.tika.pipes.fork.PipesForkParserConfig;
import org.apache.tika.pipes.fork.PipesForkResult;
import org.apache.tika.sax.BasicContentHandlerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reference sketch for IsolatedTextExtractor, verified against Tika 4.1.0.
 * Usage: java -cp "lib/*:<folder containing tika-fork-log4j2.xml>" ExtractorDemo.java <appDataDir> <files...>
 */
public class ExtractorDemo {

    /** One string per page (PDF) or slide (PPTX); everything else is a single page. */
    record ExtractedDocument(String contentType, List<String> pages, boolean truncated) {}

    static class ExtractionException extends Exception {
        ExtractionException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ the adapter

    static class IsolatedTextExtractor implements AutoCloseable {
        private final PipesForkParser parser;

        IsolatedTextExtractor(Path appDataDir) throws Exception {
            Files.createDirectories(appDataDir);
            Path tikaConfig = appDataDir.resolve("tika-config.json");
            Files.writeString(tikaConfig, "{ \"text-recognizers\": [] }\n"); // OCR off on every machine
            boolean windows = System.getProperty("os.name").startsWith("Windows");
            Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
            PipesForkParserConfig config = new PipesForkParserConfig()
                    .setJavaPath(java.toString())   // the app's own Java; the default is "java" from PATH
                    .setHandlerType(BasicContentHandlerFactory.HANDLER_TYPE.XML) // keeps page/slide <div>s
                    .setMaxEmbeddedCount(0)     // skip thumbnails and files embedded inside documents
                    .setWriteLimit(10_000_000)  // at most ~10 million characters of text per file
                    .setUserConfigPath(tikaConfig)
                    .addJvmArg("-Xmx1g")        // otherwise the child may grow to 60% of RAM
                    .addJvmArg("-Dlog4j2.configurationFile=classpath:tika-fork-log4j2.xml"); // quiet child
            parser = new PipesForkParser(config); // create once: it owns the child process
        }

        ExtractedDocument extract(Path file) throws IOException, InterruptedException, ExtractionException {
            PipesForkResult result;
            try {
                result = parser.parse(file);
            } catch (IOException | InterruptedException e) {
                throw e;
            } catch (Exception e) { // PipesException, TikaException
                throw new ExtractionException("Tika failed: " + e.getMessage());
            }
            if (result.isProcessCrash()) { // OOM, TIMEOUT, UNSPECIFIED_CRASH; the child restarts by itself
                throw new ExtractionException("parser " + result.getStatus());
            }
            if (!result.isSuccess()) { // e.g. FETCH_EXCEPTION: the file vanished or can't be opened
                throw new IOException("could not read file: " + result.getStatus());
            }
            // Careful: isSuccess() is also true when Tika could NOT read the file.
            Metadata m = result.getMetadataList().get(0);
            String xhtml = m.get(TikaCoreProperties.TIKA_CONTENT);
            String error = m.get(TikaCoreProperties.CONTAINER_EXCEPTION);
            if (xhtml == null && "true".equals(m.get(TikaCoreProperties.IS_ENCRYPTED))) {
                throw new ExtractionException("password-protected");
            }
            if (xhtml == null || error != null) {
                throw new ExtractionException("unreadable: " + (error == null ? "no text" : error.lines().findFirst().orElse("")));
            }
            String type = m.get(HttpHeaders.CONTENT_TYPE);
            boolean truncated = "true".equals(m.get(TikaCoreProperties.WRITE_LIMIT_REACHED));
            List<String> pages = PageSplitter.split(xhtml, truncated);
            if (type != null && type.startsWith("application/vnd.openxmlformats-officedocument.presentationml")
                    && !truncated) {
                pages = SlideOrder.fix(file, pages);
            }
            return new ExtractedDocument(type, pages, truncated);
        }

        @Override
        public void close() throws IOException {
            parser.close(); // stops the child process
        }
    }

    // ------------------------------------------------------------------ XHTML -> pages

    /** Splits Tika's XHTML at <div class="page"> (PDF) and <div class="slide-content"> (PPTX). */
    static class PageSplitter extends DefaultHandler {
        private final List<StringBuilder> pages = new ArrayList<>();
        private StringBuilder current;
        private boolean inBody;

        static List<String> split(String xhtml, boolean truncated) throws ExtractionException {
            PageSplitter handler = new PageSplitter();
            try {
                secureSaxFactory(false).newSAXParser().parse(new InputSource(new StringReader(xhtml)), handler);
            } catch (Exception e) {
                // When the write limit is hit, Tika's XHTML just stops mid-document; keep what was read.
                if (!truncated) throw new ExtractionException("bad XHTML from Tika: " + e.getMessage());
            }
            List<String> out = new ArrayList<>();
            for (StringBuilder page : handler.pages) {
                out.add(page.toString().replaceAll("[ \\t]*\\n\\s*", "\n").strip());
            }
            return out.isEmpty() ? List.of("") : out;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            if (qName.equals("body")) inBody = true;
            if (!inBody) return;
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
            if (qName.equals("body")) inBody = false;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inBody) append(new String(ch, start, length));
        }

        // Text after a slide's <div> (its speaker notes) stays with that slide.
        // A document with no page <div>s at all (DOCX, TXT, MD) becomes one page.
        private void append(String s) {
            if (current == null) {
                if (s.isBlank()) return;
                current = new StringBuilder();
                pages.add(current);
            }
            current.append(s);
        }
    }

    // ------------------------------------------------------------------ PPTX slide order fix

    /**
     * Tika 4.1.0 emits PPTX slides sorted by relationship id as text ("rId10" before "rId7"),
     * not in the deck's real order, which is the order of <p:sldIdLst> in ppt/presentation.xml.
     */
    static class SlideOrder {
        static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

        static List<String> fix(Path pptx, List<String> tikaPages) throws IOException {
            Map<String, String> slideRels = new TreeMap<>(); // rId -> target, sorted like Tika sorts them
            List<String> realOrder = new ArrayList<>();       // rIds in the deck's order
            try (ZipFile zip = new ZipFile(pptx.toFile())) {
                readXml(zip, "ppt/_rels/presentation.xml.rels", (name, a) -> {
                    String type = a.getValue("Type");
                    if (name.equals("Relationship") && type != null && type.endsWith("/relationships/slide")) {
                        slideRels.put(a.getValue("Id"), a.getValue("Target"));
                    }
                });
                readXml(zip, "ppt/presentation.xml", (name, a) -> {
                    if (name.equals("sldId")) realOrder.add(a.getValue(REL_NS, "id"));
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

        private static void readXml(ZipFile zip, String entryName, BiConsumer<String, Attributes> onElement)
                throws IOException {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) throw new IOException("missing " + entryName);
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] xml = in.readNBytes(5_000_000); // these parts are tiny; the cap guards against zip bombs
                secureSaxFactory(true).newSAXParser().parse(new ByteArrayInputStream(xml), new DefaultHandler() {
                    @Override
                    public void startElement(String uri, String localName, String qName, Attributes atts) {
                        onElement.accept(localName, atts);
                    }
                });
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("bad " + entryName + ": " + e.getMessage(), e);
            }
        }
    }

    static SAXParserFactory secureSaxFactory(boolean namespaceAware) throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(namespaceAware);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f;
    }

    // ------------------------------------------------------------------ demo

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        try (IsolatedTextExtractor extractor = new IsolatedTextExtractor(Path.of(args[0]))) {
            System.out.printf("startup %d ms%n", (System.nanoTime() - t0) / 1_000_000);
            for (int i = 1; i < args.length; i++) {
                Path f = Path.of(args[i]);
                long t = System.nanoTime();
                String outcome;
                try {
                    ExtractedDocument d = extractor.extract(f);
                    StringBuilder sb = new StringBuilder();
                    for (int p = 0; p < Math.min(d.pages().size(), 4); p++) {
                        String text = d.pages().get(p).replace("\n", " / ");
                        sb.append(String.format("%n      %d: %s", p + 1, text.length() > 75 ? text.substring(0, 75) + "…" : text));
                    }
                    outcome = String.format("OK, %d page(s)%s%s", d.pages().size(), d.truncated() ? ", TRUNCATED" : "", sb);
                } catch (ExtractionException e) {
                    outcome = "ExtractionException: " + e.getMessage();
                } catch (IOException e) {
                    outcome = "IOException: " + e.getMessage();
                }
                System.out.printf("%-26s %5d ms  %s%n", f.getFileName(), (System.nanoTime() - t) / 1_000_000, outcome);
            }
        }
        System.out.printf("total %d ms%n", (System.nanoTime() - t0) / 1_000_000);
    }
}
