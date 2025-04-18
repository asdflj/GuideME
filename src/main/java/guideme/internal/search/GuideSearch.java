package guideme.internal.search;

import guideme.Guide;
import guideme.Guides;
import guideme.compiler.IndexingSink;
import guideme.compiler.ParsedGuidePage;
import guideme.document.DefaultStyles;
import guideme.document.flow.LytFlowContent;
import guideme.document.flow.LytFlowSpan;
import guideme.internal.util.LangUtil;
import guideme.libs.mdast.model.MdAstHeading;
import guideme.libs.unist.UnistNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.minecraft.resources.ResourceLocation;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Search index management across all guides.
 * <p/>
 * See https://medium.com/@ekaterinamihailova/in-memory-search-and-autocomplete-with-lucene-8-5-f2df1bc71c36 for a
 * tutorial on in-memory Lucene.
 */
public class GuideSearch implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(GuideSearch.class);

    /**
     * Maximum time spent indexing per tick.
     */
    private static final long TIME_PER_TICK = TimeUnit.MILLISECONDS.toNanos(5);

    private final ByteBuffersDirectory directory = new ByteBuffersDirectory();

    private final Analyzer analyzer;
    private final IndexWriter indexWriter;
    private IndexReader indexReader;
    private final List<GuideIndexingTask> pendingTasks = new ArrayList<>();
    private Instant indexingStarted;
    private int pagesIndexed;
    private final Set<String> warnedAboutLanguage = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> indexedLanguages = Collections.synchronizedSet(new HashSet<>());

    public GuideSearch() {
        analyzer = new LanguageSpecificAnalyzerWrapper();
        var config = new IndexWriterConfig(analyzer);
        try {
            indexWriter = new IndexWriter(directory, config);
            // Flushing and committing immediately will create the necessary files for IndexReader to open successfully
            indexWriter.flush();
            indexWriter.commit();
            indexReader = DirectoryReader.open(directory);
        } catch (IOException e) {
            // This shouldn't happen since we're using an in-memory directory
            throw new UncheckedIOException("Failed to create index writer.", e);
        }
    }

    public void index(Guide guide) {
        try {
            indexWriter.deleteDocuments(new PhraseQuery(IndexSchema.FIELD_GUIDE_ID, guide.getId().toString()));
        } catch (IOException e) {
            LOG.error("Failed to delete all documents before re-indexing.", e);
        }

        if (pendingTasks.isEmpty()) {
            indexingStarted = Instant.now();
            pagesIndexed = 0;
        }
        pendingTasks.removeIf(t -> t.guide.getId().equals(guide.getId()));
        pendingTasks.add(new GuideIndexingTask(guide, new ArrayList<>(guide.getPages())));
    }

    public void indexAll() {
        for (var guide : Guides.getAll()) {
            index(guide);
        }
    }

    public void processWork() {
        if (pendingTasks.isEmpty()) {
            return;
        }

        long start = System.nanoTime();

        var guideTaskIt = pendingTasks.iterator();
        while (guideTaskIt.hasNext()) {
            if (isTimeElapsed(start)) {
                return;
            }

            var guideTask = guideTaskIt.next();
            var guide = guideTask.guide();

            var pageIt = guideTask.pendingPages.iterator();
            while (pageIt.hasNext()) {
                if (isTimeElapsed(start)) {
                    return;
                }

                var page = pageIt.next();

                var pageDoc = createPageDocument(guideTask.guide(), page);
                if (pageDoc != null) {
                    try {
                        indexWriter.addDocument(pageDoc);
                    } catch (IOException e) {
                        LOG.error("Failed to index document {}{}", guide, page, e);
                    }

                    var searchLang = pageDoc.get(IndexSchema.FIELD_SEARCH_LANG);
                    if (searchLang != null) {
                        indexedLanguages.add(searchLang);
                    }
                }
                pagesIndexed++;
                pageIt.remove();
            }

            guideTaskIt.remove();
        }

        try {
            indexWriter.flush();
            indexWriter.commit();

            indexReader.close();
            indexReader = DirectoryReader.open(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // We finished!
        LOG.info("Indexing of {} pages finished in {}", pagesIndexed, Duration.between(indexingStarted, Instant.now()));
    }

    private boolean isTimeElapsed(long start) {
        return System.nanoTime() - start >= TIME_PER_TICK;
    }

    public List<SearchResult> searchGuide(String queryText, @Nullable Guide onlyFromGuide) {
        if (queryText.isEmpty()) {
            return List.of();
        }

        var searchLanguage = getLuceneLanguageFromMinecraft(LangUtil.getCurrentLanguage());

        var indexSearcher = new IndexSearcher(indexReader);

        Query query;
        try {
            query = GuideQueryParser.parse(queryText, analyzer, indexedLanguages);
        } catch (Exception e) {
            LOG.debug("Failed to parse search query: '{}'", queryText, e);
            return List.of();
        }

        // Filter by guide if given one
        if (onlyFromGuide != null) {
            query = new BooleanQuery.Builder()
                    .add(query, BooleanClause.Occur.MUST)
                    .add(new TermQuery(new Term(IndexSchema.FIELD_GUIDE_ID, onlyFromGuide.getId().toString())),
                            BooleanClause.Occur.FILTER)
                    .build();
        }

        LOG.debug("Running GuideME search query: {}", query);

        TopDocs topDocs;
        try {
            topDocs = indexSearcher.search(query, 25);
        } catch (IOException e) {
            LOG.error("Failed to search for '{}'", queryText, e);
            return List.of();
        }

        var result = new ArrayList<SearchResult>();
        var highlighter = new Highlighter(new QueryScorer(query));
        StoredFields storedFields;
        try {
            storedFields = indexReader.storedFields();

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                var document = storedFields.document(scoreDoc.doc);
                var guideId = ResourceLocation.parse(document.get(IndexSchema.FIELD_GUIDE_ID));
                var pageId = ResourceLocation.parse(document.get(IndexSchema.FIELD_PAGE_ID));

                var guide = Guides.getById(guideId);
                if (guide == null) {
                    LOG.warn("Search index produced guide id {} which couldn't be found.", guideId);
                    continue;
                }

                var page = guide.getParsedPage(pageId);
                if (page == null) {
                    LOG.warn("Search index produced page {} in guide {}, which couldn't be found.", pageId, guideId);
                    continue;
                }

                String bestFragment = "";
                try {
                    bestFragment = highlighter.getBestFragment(analyzer, IndexSchema.getTextField(searchLanguage),
                            document.get(IndexSchema.FIELD_TEXT));
                    if (bestFragment == null) {
                        bestFragment = "";
                    }
                } catch (InvalidTokenOffsetsException e) {
                    LOG.error("Cannot determine text to highlight for result", e);
                }

                // This is kinda shit, but the Lucene highlighter isn't exactly flexible with its return type
                // it only supports strings.
                var pageTitle = document.get(IndexSchema.FIELD_TITLE);

                var startOfSegment = 0;
                LytFlowSpan currentSpan = new LytFlowSpan();
                for (int i = 0; i < bestFragment.length(); i++) {
                    if (isStartOfHighlight(bestFragment, i)) {
                        currentSpan.appendText(bestFragment.substring(startOfSegment, i));
                        startOfSegment = i + 3;
                        var parentSpan = currentSpan;
                        currentSpan = new LytFlowSpan();
                        currentSpan.setStyle(DefaultStyles.SEARCH_RESULT_HIGHLIGHT);
                        parentSpan.append(currentSpan);
                    } else if (isEndOfHighlight(bestFragment, i)) {
                        currentSpan.appendText(bestFragment.substring(startOfSegment, i));
                        startOfSegment = i + 4;
                        currentSpan = Objects.requireNonNull(((LytFlowSpan) currentSpan.getFlowParent()));
                    }
                }
                currentSpan.appendText(bestFragment.substring(startOfSegment));

                result.add(
                        new SearchResult(guideId, pageId, pageTitle, currentSpan));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return result;
    }

    private boolean isStartOfHighlight(CharSequence text, int i) {
        return (i + 3 <= text.length()) && text.charAt(i) == '<' && text.charAt(i + 1) == 'B'
                && text.charAt(i + 2) == '>';
    }

    private boolean isEndOfHighlight(CharSequence text, int i) {
        return (i + 4 <= text.length()) && text.charAt(i) == '<' && text.charAt(i + 1) == '/'
                && text.charAt(i + 2) == 'B' && text.charAt(i + 3) == '>';
    }

    @Nullable
    private Document createPageDocument(Guide guide, ParsedGuidePage page) {
        var pageText = getSearchableText(guide, page);
        var pageTitle = getPageTitle(guide, page);

        var searchLang = getLuceneLanguageFromMinecraft(page.getLanguage());

        var doc = new Document();
        doc.add(new StringField(IndexSchema.FIELD_GUIDE_ID, guide.getId().toString(), Field.Store.YES));
        doc.add(new StoredField(IndexSchema.FIELD_PAGE_ID, page.getId().toString()));
        doc.add(new StoredField(IndexSchema.FIELD_LANG, page.getLanguage()));
        doc.add(new StoredField(IndexSchema.FIELD_SEARCH_LANG, searchLang));

        // Store original text for highlighting and display purposes
        doc.add(new StoredField(IndexSchema.FIELD_TITLE, pageTitle));
        doc.add(new StoredField(IndexSchema.FIELD_TEXT, pageText));

        doc.add(new TextField(IndexSchema.getTitleField(searchLang), pageTitle, Field.Store.NO));
        doc.add(new TextField(IndexSchema.getTextField(searchLang), pageText, Field.Store.NO));
        return doc;
    }

    private String getLuceneLanguageFromMinecraft(String language) {
        var luceneLang = Analyzers.MINECRAFT_TO_LUCENE_LANG.get(language);
        if (luceneLang == null) {
            if (warnedAboutLanguage.add(language)) {
                LOG.warn(
                        "Minecraft language '{}' has unknown and will be treated as english for the purposes of search.",
                        language);
            }
            return Analyzers.LANG_ENGLISH;
        }
        return luceneLang;
    }

    private static String getPageTitle(Guide guide, ParsedGuidePage page) {

        // Navigation title in frontmatter wins
        var navigationEntry = page.getFrontmatter().navigationEntry();
        if (navigationEntry != null) {
            return navigationEntry.title();
        }

        // Find the first heading (same logic as in GuideScreen)
        for (var child : page.getAstRoot().children()) {
            if (child instanceof MdAstHeading heading && heading.depth == 1) {
                var pageTitle = new StringBuilder();
                var sink = new IndexingSink() {
                    @Override
                    public void appendText(UnistNode parent, String text) {
                        pageTitle.append(text);
                    }

                    @Override
                    public void appendBreak() {
                        pageTitle.append(' ');
                    }
                };
                new PageIndexer(guide, guide.getExtensions(), page.getId()).indexContent(heading.children(), sink);
                return pageTitle.toString();
            }
        }

        return page.getId().toString();
    }

    private static String getSearchableText(Guide guide, ParsedGuidePage page) {
        var searchableText = new StringBuilder();

        var sink = new IndexingSink() {
            @Override
            public void appendText(UnistNode parent, String text) {
                searchableText.append(text);
            }

            @Override
            public void appendBreak() {
                searchableText.append('\n');
            }
        };
        new PageIndexer(guide, guide.getExtensions(), page.getId()).index(page.getAstRoot(), sink);
        return searchableText.toString();
    }

    @Override
    public void close() throws IOException {
        IOException suppressed = null;
        try {
            indexWriter.close();
        } catch (IOException e) {
            suppressed = e;
        }
        try {
            indexReader.close();
        } catch (IOException e) {
            if (suppressed != null) {
                suppressed.addSuppressed(e);
            } else {
                suppressed = e;
            }
        }
        try {
            directory.close();
        } catch (IOException e) {
            if (suppressed != null) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        if (suppressed != null) {
            throw suppressed;
        }
    }

    record GuideIndexingTask(Guide guide, List<ParsedGuidePage> pendingPages) {
    }

    public record SearchResult(ResourceLocation guideId, ResourceLocation pageId, String pageTitle,
            LytFlowContent text) {
        public SearchResult {
            Objects.requireNonNull(guideId, "guideId");
            Objects.requireNonNull(pageId, "pageId");
            Objects.requireNonNull(pageTitle, "pageTitle");
            Objects.requireNonNull(text, "text");
        }
    }
}
