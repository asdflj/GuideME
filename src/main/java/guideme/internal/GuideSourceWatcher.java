package guideme.internal;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import guideme.GuidePageChange;
import guideme.compiler.PageCompiler;
import guideme.compiler.ParsedGuidePage;
import guideme.internal.util.LangUtil;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryChangeListener;
import io.methvin.watcher.DirectoryWatcher;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GuideSourceWatcher implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(GuideSourceWatcher.class);

    private final String defaultLanguage;

    /**
     * The {@link ResourceLocation} namespace to use for files in the watched folder.
     */
    private final String namespace;
    /**
     * The ID of the resource pack to use as the source pack.
     */
    private final String sourcePackId;

    private final Path sourceFolder;

    // Recursive directory watcher for the guidebook sources.
    @Nullable
    private final DirectoryWatcher sourceWatcher;

    // Queued changes that come in from a separate thread
    private record PageLangKey(String sourceLang, ResourceLocation pageId) {
    }

    private final Map<PageLangKey, ParsedGuidePage> changedPages = new HashMap<>();
    private final Set<PageLangKey> deletedPages = new HashSet<>();

    private final ExecutorService watchExecutor;

    public GuideSourceWatcher(String namespace, String defaultLanguage, Path sourceFolder) {
        this.namespace = namespace;
        // The namespace does not necessarily *need* to be a mod id, but if it is, the source pack needs to
        // follow the specific mod-id format. Otherwise we assume it's a resource pack where namespace == pack id,
        // which is also not 100% correct.
        this.sourcePackId = ModList.get().isLoaded(namespace) ? "mod:" + namespace : namespace;
        this.defaultLanguage = defaultLanguage;
        this.sourceFolder = sourceFolder;
        if (!Files.isDirectory(sourceFolder)) {
            throw new RuntimeException("Cannot find the specified folder with guidebook sources: "
                    + sourceFolder);
        }
        LOG.info("Watching guidebook sources in {}", sourceFolder);

        watchExecutor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("GuideMELiveReloadWatcher%d")
                .build());

        // Watch the folder recursively in a separate thread, queue up any changes and apply them
        // in the client tick.
        DirectoryWatcher watcher;
        try {
            watcher = DirectoryWatcher.builder()
                    .path(sourceFolder)
                    .fileHashing(false)
                    .listener(new Listener())
                    .build();
        } catch (IOException e) {
            LOG.error("Failed to watch for changes in the guidebook sources at {}", sourceFolder, e);
            watcher = null;
        }
        sourceWatcher = watcher;

        // Actually process changes in the client tick to prevent race conditions and other crashes
        if (sourceWatcher != null) {
            sourceWatcher.watchAsync(watchExecutor);
        }
    }

    public List<ParsedGuidePage> loadAll(String defaultLanguage) {
        var stopwatch = Stopwatch.createStarted();

        var currentLanguage = LangUtil.getCurrentLanguage();
        var validLanguages = LangUtil.getValidLanguages();

        // Find all potential pages
        var pagesToLoad = new HashMap<ResourceLocation, Path>();
        try {
            Files.walkFileTree(sourceFolder, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    var pageId = getPageId(file);
                    if (pageId != null) {
                        pagesToLoad.put(pageId, file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    LOG.error("Failed to list page {}", file, exc);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (exc != null) {
                        LOG.error("Failed to list all pages in {}", dir, exc);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.error("Failed to list all pages in {}", sourceFolder, e);
        }

        LOG.info("Loading {} guidebook pages", pagesToLoad.size());
        var loadedPages = pagesToLoad.entrySet()
                .stream()
                .map(entry -> {
                    var pageId = entry.getKey();
                    var path = entry.getValue();

                    if (LangUtil.getLangFromPageId(pageId, validLanguages) != null) {
                        return null; // Skip translated pages
                    }
                    var translatedPage = pagesToLoad.get(LangUtil.getTranslatedAsset(pageId, currentLanguage));
                    String language;
                    if (translatedPage != null) {
                        language = currentLanguage;
                        path = translatedPage;
                    } else {
                        language = defaultLanguage;
                    }

                    try (var in = Files.newInputStream(path)) {
                        return PageCompiler.parse(sourcePackId, language, pageId, in);

                    } catch (Exception e) {
                        LOG.error("Failed to reload guidebook page {}", path, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();

        LOG.info("Loaded {} pages from {} in {}", loadedPages.size(), sourceFolder, stopwatch);

        return loadedPages;
    }

    public synchronized void clearChanges() {
        changedPages.clear();
        deletedPages.clear();
    }

    public synchronized List<GuidePageChange> takeChanges() {

        if (deletedPages.isEmpty() && changedPages.isEmpty()) {
            return List.of();
        }

        var changes = new ArrayList<GuidePageChange>();

        for (var deletedPage : deletedPages) {
            ParsedGuidePage newPage = null;
            changes.add(new GuidePageChange(deletedPage.sourceLang(), deletedPage.pageId(), null, newPage));
        }
        deletedPages.clear();

        for (var entry : changedPages.entrySet()) {
            var pageKey = entry.getKey();
            var changedPage = entry.getValue();
            changes.add(new GuidePageChange(pageKey.sourceLang(), pageKey.pageId(), null, changedPage));
        }
        changedPages.clear();

        return changes;
    }

    public synchronized void close() {
        changedPages.clear();
        deletedPages.clear();
        watchExecutor.shutdown();

        if (sourceWatcher != null) {
            try {
                sourceWatcher.close();
            } catch (IOException e) {
                LOG.error("Failed to close fileystem watcher for {}", sourceFolder);
            }
        }
    }

    private class Listener implements DirectoryChangeListener {
        @Override
        public void onEvent(DirectoryChangeEvent event) {
            if (event.isDirectory()) {
                return;
            }
            switch (event.eventType()) {
                case CREATE, MODIFY -> pageChanged(event.path());
                case DELETE -> pageDeleted(event.path());
            }
        }

        @Override
        public boolean isWatching() {
            return sourceWatcher != null && !sourceWatcher.isClosed();
        }

        @Override
        public void onException(Exception e) {
            LOG.error("Failed watching for changes", e);
        }
    }

    // Only call while holding the lock!
    private synchronized void pageChanged(Path path) {
        var pageKey = getPageLangKey(path);
        if (pageKey == null) {
            return; // Probably not a page
        }

        var language = Objects.requireNonNullElse(pageKey.sourceLang(), defaultLanguage);

        // If it was previously deleted in the same change-set, undelete it
        deletedPages.remove(pageKey);

        try (var in = Files.newInputStream(path)) {
            var page = PageCompiler.parse(sourcePackId, language, pageKey.pageId, in);
            changedPages.put(pageKey, page);
        } catch (Exception e) {
            LOG.error("Failed to reload guidebook page {}", path, e);
        }
    }

    // Only call while holding the lock!
    private synchronized void pageDeleted(Path path) {
        var pageKey = getPageLangKey(path);
        if (pageKey == null) {
            return; // Probably not a page
        }

        // If a language specific page is deleted, make it fall back to the default language page instead
        var defaultLangPath = sourceFolder.resolve(pageKey.pageId().toString());
        if (!defaultLangPath.equals(path)) {
            try (var in = Files.newInputStream(defaultLangPath)) {
                var page = PageCompiler.parse(sourcePackId, defaultLanguage, pageKey.pageId(), in);
                changedPages.put(pageKey, page);
                deletedPages.remove(pageKey);
                return;
            } catch (Exception e) {
                LOG.error("Failed to load default language guidebook page {}", path, e);
            }
        }

        // If it was previously changed in the same change-set, remove the change
        changedPages.remove(pageKey);
        deletedPages.add(pageKey);
    }

    @Nullable
    private ResourceLocation getPageId(Path path) {
        var relativePath = sourceFolder.relativize(path);
        var relativePathStr = relativePath.toString().replace('\\', '/');
        if (!relativePathStr.endsWith(".md")) {
            return null;
        }
        if (!ResourceLocation.isValidPath(relativePathStr)) {
            return null;
        }
        return ResourceLocation.fromNamespaceAndPath(namespace, relativePathStr);
    }

    @Nullable
    private PageLangKey getPageLangKey(Path path) {
        var pageId = getPageId(path);
        if (pageId == null) {
            return null;
        }
        var languages = LangUtil.getValidLanguages();
        var lang = LangUtil.getLangFromPageId(pageId, languages);
        if (lang != null) {
            pageId = LangUtil.stripLangFromPageId(pageId, languages);
        }
        return new PageLangKey(lang, pageId);
    }
}
