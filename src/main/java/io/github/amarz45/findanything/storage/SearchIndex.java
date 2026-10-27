package io.github.amarz45.findanything.storage;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.util.BytesRef;

/**
 * The searchable index, kept in a folder on disk. Lucene stays inside this class.
 *
 * <p>There is one entry per chunk of each indexed file, stored together with the file's attributes
 * (path, type, size, modification time, status), so filters apply inside a query, a removed file
 * disappears from results immediately, and two copies of a file are two separate results. A file
 * without text (unsupported format, failed extraction, no text layer) has one entry with no chunk,
 * so it is still listed and found by name.
 *
 * <p>{@link #open} takes the index's write lock if it can ({@link IndexMode#WRITER}); otherwise it
 * opens the index {@link IndexMode#READ_ONLY}: the first process wins. Single writer: only the
 * indexing worker thread calls {@link #put}, {@link #remove} and {@link #commit}. Reads are safe
 * from any thread and see the last committed snapshot; a read-only process calls {@link #refresh}
 * every few seconds to pick up the writer's commits.
 */
public final class SearchIndex implements AutoCloseable {

    private static final String PATH = "path";
    private static final String FILE_NAME = "fileName";
    private static final String FILE_TYPE = "fileType";
    private static final String SIZE_BYTES = "sizeBytes";
    private static final String MODIFIED_TIME = "modifiedTime";
    private static final String CONTENT_HASH = "contentHash";
    private static final String EXTRACTION_STATUS = "extractionStatus";
    private static final String POSITION = "position";
    private static final String PAGE_NUMBER = "pageNumber";
    private static final String TEXT = "text";

    /** Marks the first entry of each file, so listing files returns each file once. */
    private static final String FIRST = "first";

    /** The position stored on a file's single entry when it has no chunks. */
    private static final int NO_CHUNK = -1;

    private static final String SEPARATOR = FileSystems.getDefault().getSeparator();

    private final Path indexDirectory;
    private final IndexMode mode;
    private final Directory directory;
    private final Analyzer analyzer;
    private final IndexWriter writer; // null in READ_ONLY mode
    private volatile SearcherManager searcherManager; // null until the index exists on disk

    private SearchIndex(
            Path indexDirectory,
            IndexMode mode,
            Directory directory,
            Analyzer analyzer,
            IndexWriter writer,
            SearcherManager searcherManager) {
        this.indexDirectory = indexDirectory;
        this.mode = mode;
        this.directory = directory;
        this.analyzer = analyzer;
        this.writer = writer;
        this.searcherManager = searcherManager;
    }

    /**
     * Opens the index in the folder, creating it when it doesn't exist. Takes the write lock if no
     * other process holds it; otherwise opens read-only.
     */
    public static SearchIndex open(Path indexDirectory) throws IOException {
        Path folder =
                Objects.requireNonNull(indexDirectory, "indexDirectory")
                        .toAbsolutePath()
                        .normalize();
        Files.createDirectories(folder);
        Directory directory = FSDirectory.open(folder);
        Analyzer analyzer = new FoldingAnalyzer();
        IndexWriter writer = null;
        IndexMode mode;
        try {
            writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
            writer.commit(); // make sure the index exists on disk, so readers can open it
            mode = IndexMode.WRITER;
        } catch (LockObtainFailedException e) {
            mode = IndexMode.READ_ONLY;
        } catch (IOException e) {
            directory.close();
            throw e;
        }
        SearcherManager searcherManager =
                DirectoryReader.indexExists(directory)
                        ? new SearcherManager(directory, null)
                        : null;
        return new SearchIndex(folder, mode, directory, analyzer, writer, searcherManager);
    }

    public IndexMode getMode() {
        return mode;
    }

    public Path getIndexDirectory() {
        return indexDirectory;
    }

    // ------------------------------------------------------------------ writes (worker thread)

    /**
     * Stores a file and its chunks, replacing whatever the index held for that path. A file with no
     * chunks gets a single entry so it is still listed and found by name.
     */
    public void put(IndexedFile file, List<Chunk> chunks) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(chunks, "chunks");
        requireWriter();
        String path = key(file.path());
        writer.deleteDocuments(new Term(PATH, path));
        if (chunks.isEmpty()) {
            writer.addDocument(document(file, path, null, true));
            return;
        }
        boolean first = true;
        for (Chunk chunk : chunks) {
            writer.addDocument(document(file, path, chunk, first));
            first = false;
        }
    }

    /** Removes a file, or every file under a folder, from the index. */
    public void remove(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        requireWriter();
        String key = key(path);
        writer.deleteDocuments(
                new TermQuery(new Term(PATH, key)),
                new PrefixQuery(new Term(PATH, key + SEPARATOR)));
    }

    /** Makes every change so far durable and visible to readers. */
    public void commit() throws IOException {
        requireWriter();
        writer.commit();
        refresh();
    }

    private void requireWriter() {
        if (writer == null) {
            throw new IllegalStateException(
                    "The index is open read-only: another process owns it.");
        }
    }

    // ------------------------------------------------------------------ reads (any thread)

    /** Picks up the latest commit. A read-only process calls this every few seconds. */
    public void refresh() throws IOException {
        if (searcherManager == null) {
            synchronized (this) {
                if (searcherManager == null && DirectoryReader.indexExists(directory)) {
                    searcherManager = new SearcherManager(directory, null);
                }
            }
            return;
        }
        searcherManager.maybeRefreshBlocking();
    }

    /** The file's record, if the path is indexed. */
    public Optional<IndexedFile> getFile(Path file) throws IOException {
        Query query = and(new TermQuery(new Term(PATH, key(file))), firstOnly());
        return withSearcher(
                searcher -> {
                    TopDocs top = searcher.search(query, 1);
                    if (top.scoreDocs.length == 0) {
                        return Optional.empty();
                    }
                    return Optional.of(
                            toFile(searcher.storedFields().document(top.scoreDocs[0].doc)));
                },
                Optional.empty());
    }

    /** The file's chunks in order; empty when the file isn't indexed or has no text. */
    public List<Chunk> getChunks(Path file) throws IOException {
        Query query = new TermQuery(new Term(PATH, key(file)));
        return withSearcher(
                searcher -> {
                    int count = searcher.count(query);
                    if (count == 0) {
                        return List.of();
                    }
                    TopDocs top =
                            searcher.search(
                                    query,
                                    count,
                                    new Sort(new SortField(POSITION, SortField.Type.LONG)));
                    List<Chunk> chunks = new ArrayList<>();
                    StoredFields stored = searcher.storedFields();
                    for (ScoreDoc hit : top.scoreDocs) {
                        Chunk chunk = toChunk(stored.document(hit.doc));
                        if (chunk != null) {
                            chunks.add(chunk);
                        }
                    }
                    return chunks;
                },
                List.of());
    }

    /**
     * Finds the chunks that best match the words of the query, by text or file name, with the
     * filters applied inside the query. Special characters in the query are taken literally.
     * Accents are ignored (résumé matches resume). Each file's entries are separate hits.
     *
     * @return at most {@code limit} hits, best first; empty for a blank query
     */
    public List<ChunkHit> keywordSearch(String query, SearchFilters searchFilters, int limit)
            throws IOException {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(searchFilters, "searchFilters");
        if (query.isBlank() || limit <= 0) {
            return List.of();
        }
        Query textQuery = parse(query);
        Query full = and(textQuery, filterClauses(searchFilters));
        return withSearcher(
                searcher -> {
                    TopDocs top = searcher.search(full, limit);
                    List<ChunkHit> hits = new ArrayList<>();
                    StoredFields stored = searcher.storedFields();
                    for (ScoreDoc hit : top.scoreDocs) {
                        Document document = stored.document(hit.doc);
                        hits.add(new ChunkHit(toFile(document), toChunk(document), hit.score));
                    }
                    return hits;
                },
                List.of());
    }

    /** Lists indexed files that pass the filters, newest first. */
    public List<IndexedFile> listFiles(SearchFilters searchFilters, int limit) throws IOException {
        Objects.requireNonNull(searchFilters, "searchFilters");
        if (limit <= 0) {
            return List.of();
        }
        Query query = and(firstOnly(), filterClauses(searchFilters));
        Sort newestFirst = new Sort(new SortField(MODIFIED_TIME, SortField.Type.LONG, true));
        return withSearcher(
                searcher -> {
                    TopDocs top = searcher.search(query, limit, newestFirst);
                    return toFiles(searcher, top);
                },
                List.of());
    }

    /** Lists every indexed file with the given extraction status, sorted by path. */
    public List<IndexedFile> listFiles(ExtractionStatus status) throws IOException {
        Objects.requireNonNull(status, "status");
        Query query = and(firstOnly(), new TermQuery(new Term(EXTRACTION_STATUS, status.name())));
        return withSearcher(
                searcher -> {
                    int count = searcher.count(query);
                    if (count == 0) {
                        return List.of();
                    }
                    List<IndexedFile> files =
                            new ArrayList<>(toFiles(searcher, searcher.search(query, count)));
                    files.sort(Comparator.comparing(IndexedFile::path));
                    return files;
                },
                List.of());
    }

    /** The number of indexed files. */
    public int countFiles() throws IOException {
        return withSearcher(searcher -> searcher.count(firstOnly()), 0);
    }

    @Override
    public void close() throws IOException {
        if (searcherManager != null) {
            searcherManager.close();
        }
        if (writer != null) {
            writer.close(); // commits and releases the write lock
        }
        directory.close();
        analyzer.close();
    }

    // ------------------------------------------------------------------ helpers

    private interface SearchAction<T> {
        T run(IndexSearcher searcher) throws IOException;
    }

    /**
     * Runs an action against the current snapshot, or returns {@code whenEmpty} if there is none.
     */
    private <T> T withSearcher(SearchAction<T> action, T whenEmpty) throws IOException {
        SearcherManager manager = searcherManager;
        if (manager == null) {
            return whenEmpty;
        }
        IndexSearcher searcher = manager.acquire();
        try {
            return action.run(searcher);
        } finally {
            manager.release(searcher);
        }
    }

    private static String key(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static Query firstOnly() {
        return new TermQuery(new Term(FIRST, "true"));
    }

    private static Query and(Query must, List<Query> filters) {
        if (filters.isEmpty()) {
            return must;
        }
        BooleanQuery.Builder builder =
                new BooleanQuery.Builder().add(must, BooleanClause.Occur.MUST);
        filters.forEach(filter -> builder.add(filter, BooleanClause.Occur.FILTER));
        return builder.build();
    }

    private static Query and(Query must, Query filter) {
        return and(must, List.of(filter));
    }

    private Query parse(String query) throws IOException {
        MultiFieldQueryParser parser =
                new MultiFieldQueryParser(
                        new String[] {TEXT, FILE_NAME}, analyzer, Map.of(FILE_NAME, 2.0f));
        parser.setDefaultOperator(QueryParser.Operator.OR);
        try {
            return parser.parse(QueryParser.escape(query.strip()));
        } catch (ParseException e) {
            throw new IOException("The query could not be parsed: " + e.getMessage(), e);
        }
    }

    private static List<Query> filterClauses(SearchFilters filters) {
        List<Query> clauses = new ArrayList<>();
        if (!filters.fileTypes().isEmpty()) {
            clauses.add(
                    new TermInSetQuery(
                            FILE_TYPE, filters.fileTypes().stream().map(BytesRef::new).toList()));
        }
        if (filters.modifiedAfter() != null || filters.modifiedBefore() != null) {
            long from =
                    filters.modifiedAfter() == null
                            ? Long.MIN_VALUE
                            : startOfDay(filters.modifiedAfter());
            long to =
                    filters.modifiedBefore() == null
                            ? Long.MAX_VALUE
                            : startOfDay(filters.modifiedBefore().plusDays(1)) - 1;
            clauses.add(LongPoint.newRangeQuery(MODIFIED_TIME, from, to));
        }
        if (filters.minSizeBytes() >= 0 || filters.maxSizeBytes() >= 0) {
            long min = Math.max(filters.minSizeBytes(), 0);
            long max = filters.maxSizeBytes() < 0 ? Long.MAX_VALUE : filters.maxSizeBytes();
            clauses.add(LongPoint.newRangeQuery(SIZE_BYTES, min, max));
        }
        if (filters.location() != null) {
            String folder = key(filters.location());
            clauses.add(
                    new BooleanQuery.Builder()
                            .add(new TermQuery(new Term(PATH, folder)), BooleanClause.Occur.SHOULD)
                            .add(
                                    new PrefixQuery(new Term(PATH, folder + SEPARATOR)),
                                    BooleanClause.Occur.SHOULD)
                            .build());
        }
        return clauses;
    }

    private static long startOfDay(LocalDate day) {
        return day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static Document document(IndexedFile file, String path, Chunk chunk, boolean first) {
        Document document = new Document();
        document.add(new StringField(PATH, path, Field.Store.YES));
        document.add(new TextField(FILE_NAME, nameWords(file.path()), Field.Store.NO));
        document.add(new StringField(FILE_TYPE, file.fileType(), Field.Store.YES));
        document.add(new LongPoint(SIZE_BYTES, file.sizeBytes()));
        document.add(new StoredField(SIZE_BYTES, file.sizeBytes()));
        long modified = file.modifiedTime().toEpochMilli();
        document.add(new LongPoint(MODIFIED_TIME, modified));
        document.add(new NumericDocValuesField(MODIFIED_TIME, modified));
        document.add(new StoredField(MODIFIED_TIME, modified));
        document.add(new StringField(CONTENT_HASH, file.contentHash(), Field.Store.YES));
        document.add(
                new StringField(
                        EXTRACTION_STATUS, file.extractionStatus().name(), Field.Store.YES));
        if (first) {
            document.add(new StringField(FIRST, "true", Field.Store.NO));
        }
        int position = chunk == null ? NO_CHUNK : chunk.position();
        document.add(new NumericDocValuesField(POSITION, position));
        document.add(new StoredField(POSITION, position));
        if (chunk != null) {
            document.add(new StoredField(PAGE_NUMBER, chunk.pageNumber()));
            document.add(new TextField(TEXT, chunk.text(), Field.Store.YES));
        }
        return document;
    }

    /**
     * The file's name as indexed: whole, and also split at dots, dashes and other separators, so
     * "photo" finds "photo.png" and "budget" finds "project-budget-2026.txt".
     */
    private static String nameWords(Path path) {
        String name = path.getFileName().toString();
        return name + " " + name.replaceAll("[^\\p{L}\\p{N}]+", " ");
    }

    private static IndexedFile toFile(Document document) {
        return new IndexedFile(
                Path.of(document.get(PATH)),
                document.get(FILE_TYPE),
                document.getField(SIZE_BYTES).numericValue().longValue(),
                Instant.ofEpochMilli(document.getField(MODIFIED_TIME).numericValue().longValue()),
                document.get(CONTENT_HASH),
                ExtractionStatus.valueOf(document.get(EXTRACTION_STATUS)));
    }

    /** The entry's chunk, or null for a file's placeholder entry. */
    private static Chunk toChunk(Document document) {
        int position = document.getField(POSITION).numericValue().intValue();
        if (position == NO_CHUNK) {
            return null;
        }
        return new Chunk(
                position,
                document.getField(PAGE_NUMBER).numericValue().intValue(),
                document.get(TEXT),
                new float[0]);
    }

    private static List<IndexedFile> toFiles(IndexSearcher searcher, TopDocs top)
            throws IOException {
        List<IndexedFile> files = new ArrayList<>();
        StoredFields stored = searcher.storedFields();
        for (ScoreDoc hit : top.scoreDocs) {
            files.add(toFile(stored.document(hit.doc)));
        }
        return files;
    }

    /** Standard tokens, lowercased, with accents folded away so "résumé" and "resume" match. */
    private static final class FoldingAnalyzer extends Analyzer {
        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            Tokenizer tokenizer = new StandardTokenizer();
            TokenStream stream = new ASCIIFoldingFilter(new LowerCaseFilter(tokenizer));
            return new TokenStreamComponents(tokenizer, stream);
        }
    }
}
