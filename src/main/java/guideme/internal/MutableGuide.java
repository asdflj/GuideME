package guideme.internal;

import guideme.Guide;
import guideme.GuideItemSettings;
import guideme.GuidePage;
import guideme.GuidePageChange;
import guideme.compiler.PageCompiler;
import guideme.compiler.ParsedGuidePage;
import guideme.extensions.ExtensionCollection;
import guideme.indices.PageIndex;
import guideme.internal.screen.GuideScreen;
import guideme.internal.util.LangUtil;
import guideme.navigation.NavigationTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates a Guide, which consists of a collection of Markdown pages and associated content, loaded from a
 * guide-specific subdirectory of resource packs.
 */
public final class MutableGuide implements Guide {
    private static final Logger LOG = LoggerFactory.getLogger(MutableGuide.class);

    private final ResourceLocation id;
    private final String defaultNamespace;
    private final String folder;
    private final String defaultLanguage;
    private final ResourceLocation startPage;
    private final Map<ResourceLocation, ParsedGuidePage> developmentPages = new HashMap<>();
    private final Map<Class<?>, PageIndex> indices;
    private NavigationTree navigationTree = new NavigationTree();
    /**
     * These are only loaded for the current language and backfilled by default language pages.
     */
    private Map<ResourceLocation, ParsedGuidePage> pages;
    private final ExtensionCollection extensions;
    private final boolean availableToOpenHotkey;
    private final GuideItemSettings itemSettings;

    @Nullable
    private final Path developmentSourceFolder;
    @Nullable
    private final String developmentSourceNamespace;

    @Nullable
    private GuideSourceWatcher watcher;

    public MutableGuide(ResourceLocation id,
            String defaultNamespace,
            String folder,
            String defaultLanguage,
            ResourceLocation startPage,
            @Nullable Path developmentSourceFolder,
            @Nullable String developmentSourceNamespace,
            Map<Class<?>, PageIndex> indices,
            ExtensionCollection extensions,
            boolean availableToOpenHotkey,
            GuideItemSettings itemSettings) {
        this.id = id;
        this.defaultNamespace = defaultNamespace;
        this.folder = folder;
        this.defaultLanguage = defaultLanguage;
        this.startPage = startPage;
        this.developmentSourceFolder = developmentSourceFolder;
        this.developmentSourceNamespace = developmentSourceNamespace;
        this.indices = indices;
        this.extensions = extensions;
        this.availableToOpenHotkey = availableToOpenHotkey;
        this.itemSettings = itemSettings;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public ResourceLocation getStartPage() {
        return startPage;
    }

    @Override
    public String getDefaultNamespace() {
        return defaultNamespace;
    }

    /**
     * The resource pack subfolder that is the content root for this guide.
     */
    @Override
    public String getContentRootFolder() {
        return folder;
    }

    @Override
    public <T extends PageIndex> T getIndex(Class<T> indexClass) {
        var index = indices.get(indexClass);
        if (index == null) {
            throw new IllegalArgumentException("No index of type " + indexClass + " is registered with this guide.");
        }
        return indexClass.cast(index);
    }

    @Override
    @Nullable
    public ParsedGuidePage getParsedPage(ResourceLocation id) {
        if (pages == null) {
            LOG.warn("Can't get page {}. Pages not loaded yet.", id);
            return null;
        }

        return developmentPages.getOrDefault(id, pages.get(id));
    }

    @Override
    @Nullable
    public GuidePage getPage(ResourceLocation id) {
        var page = getParsedPage(id);

        return page != null ? PageCompiler.compile(this, extensions, page) : null;
    }

    @Override
    public Collection<ParsedGuidePage> getPages() {
        if (pages == null) {
            throw new IllegalStateException("Pages are not loaded yet.");
        }

        if (developmentPages.isEmpty()) {
            return this.pages.values();
        }

        var pages = new LinkedHashMap<>(this.pages);
        pages.putAll(developmentPages);
        return pages.values();
    }

    @Override
    public byte[] loadAsset(ResourceLocation id) {
        // Try loading the language specific version first
        var language = LangUtil.getCurrentLanguage();
        if (!GuideMEClient.instance().isIgnoreTranslatedGuides() && !Objects.equals(language, defaultLanguage)) {
            var result = loadAssetInternal(id.withPrefix("_" + language + "/"));
            if (result != null) {
                return result;
            }
        }
        return loadAssetInternal(id);
    }

    private byte @Nullable [] loadAssetInternal(ResourceLocation id) {
        // Also load images from the development sources folder, if it exists and contains the asset namespace
        if (developmentSourceFolder != null && id.getNamespace().equals(developmentSourceNamespace)) {
            var path = developmentSourceFolder.resolve(id.getPath());
            try (var in = Files.newInputStream(path)) {
                return in.readAllBytes();
            } catch (NoSuchFileException ignored) {
            } catch (IOException e) {
                LOG.error("Failed to open guidebook asset {}", path);
                return null;
            }
        }

        // Transform id such that the path is prefixed with "ae2assets", the source folder for the guidebook assets
        id = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), folder + "/" + id.getPath());

        var resource = Minecraft.getInstance().getResourceManager().getResource(id).orElse(null);
        if (resource == null) {
            return null;
        }
        try (var input = resource.open()) {
            return input.readAllBytes();
        } catch (IOException e) {
            LOG.error("Failed to open guidebook asset {}", id);
            return null;
        }
    }

    @Override
    public NavigationTree getNavigationTree() {
        return navigationTree;
    }

    @Override
    public boolean pageExists(ResourceLocation pageId) {
        return developmentPages.containsKey(pageId) || pages != null && pages.containsKey(pageId);
    }

    /**
     * Returns the on-disk path for a given guidebook resource (i.e. page, asset) if development mode is enabled and the
     * resource exists in the development source folder.
     *
     * @return null if development mode is not enabled or the resource doesn't exist in the development sources.
     */
    @Nullable
    public Path getDevelopmentSourcePath(ResourceLocation id) {
        if (developmentSourceFolder != null && id.getNamespace().equals(developmentSourceNamespace)) {
            var path = developmentSourceFolder.resolve(id.getPath());
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }

    @Nullable
    public Path getDevelopmentSourceFolder() {
        return developmentSourceFolder;
    }

    @Override
    public ExtensionCollection getExtensions() {
        return extensions;
    }

    /**
     * @return True if this guide should be considered for use in the global open guide hotkey.
     */
    public boolean isAvailableToOpenHotkey() {
        return availableToOpenHotkey;
    }

    public void watchDevelopmentSources() {
        if (watcher != null) {
            return;
        }

        watcher = new GuideSourceWatcher(developmentSourceNamespace, defaultLanguage, developmentSourceFolder);
        Runtime.getRuntime().addShutdownHook(new Thread(watcher::close));
    }

    public void tick() {
        if (pages == null || watcher == null) {
            return; // Do nothing while pages haven't been loaded yet
        }

        var changes = watcher.takeChanges();
        if (!changes.isEmpty()) {
            applyChanges(changes);
        }
    }

    private boolean isForDefaultLanguage(GuidePageChange change) {
        return change.language() == null || defaultLanguage.equals(change.language());
    }

    private void applyChanges(List<GuidePageChange> changes) {
        var initialPages = new HashMap<>(developmentPages);

        var currentLanguage = LangUtil.getCurrentLanguage();
        // Remove redundant changes
        for (int i = changes.size() - 1; i >= 0; i--) {
            var change = changes.get(i);
            if (change == null) {
                continue;
            }

            // Delete changes for languages that aren't relevant
            if (!isForDefaultLanguage(change) && !currentLanguage.equals(change.language())) {
                changes.set(i, null);
            } else {
                for (int j = 0; j < i; j++) {
                    var prevChange = changes.get(j);
                    if (prevChange != null && prevChange.pageId().equals(change.pageId())) {
                        changes.set(j, null);
                    }
                }
            }
        }
        changes.removeIf(Objects::isNull); // remove erased changes

        // Enrich each change with the previous page data while we process them
        for (int i = 0; i < changes.size(); i++) {
            var change = changes.get(i);
            var pageId = change.pageId();

            // Handle language changes
            var currentPage = developmentPages.get(pageId);
            if (currentPage != null && !defaultLanguage.equals(currentPage.getLanguage())
                    && isForDefaultLanguage(change)) {
                changes.set(i, null);
                continue;
            }

            var newPage = change.newPage();
            if (newPage != null) {
                developmentPages.put(pageId, newPage);
            } else {
                developmentPages.remove(pageId);
            }

            changes.set(i, new GuidePageChange(change.language(), pageId, initialPages.get(pageId), newPage));
        }
        changes.removeIf(Objects::isNull); // Remove changes not relevant for the current language

        // Allow indices to rebuild
        var allPages = new ArrayList<ParsedGuidePage>(pages.size() + developmentPages.size());
        allPages.addAll(pages.values());
        allPages.addAll(developmentPages.values());
        for (var index : indices.values()) {
            if (index.supportsUpdate()) {
                index.update(allPages, changes);
            } else {
                index.rebuild(allPages);
            }
        }

        // Rebuild navigation
        this.navigationTree = buildNavigation();

        // Reload the current page if it has been changed
        if (Minecraft.getInstance().screen instanceof GuideScreen guideScreen) {
            var currentPageId = guideScreen.getCurrentPageId();
            if (changes.stream().anyMatch(c -> c.pageId().equals(currentPageId))) {
                guideScreen.reloadPage();
            }
        }
    }

    private NavigationTree buildNavigation() {
        if (developmentPages.isEmpty()) {
            return NavigationTree.build(pages.values());
        } else {
            var allPages = new HashMap<>(pages);
            allPages.putAll(developmentPages);
            return NavigationTree.build(allPages.values());
        }
    }

    public void validateAll() {
        // Iterate and compile all pages to warn about errors on startup
        for (var entry : developmentPages.entrySet()) {
            LOG.info("Compiling {}", entry.getKey());
            getPage(entry.getKey());
        }
    }

    @ApiStatus.Internal
    public void rebuildIndices() {
        var allPages = new ArrayList<>(getPages());
        for (var index : indices.values()) {
            index.rebuild(allPages);
        }
    }

    public void setPages(Map<ResourceLocation, ParsedGuidePage> pages) {
        this.pages = Map.copyOf(pages);

        if (watcher != null) {
            watcher.clearChanges(); // Since we'll load them all now, ignore all changes up to now

            for (var page : watcher.loadAll(defaultLanguage)) {
                developmentPages.put(page.getId(), page);
            }
        }

        rebuildIndices();
        navigationTree = buildNavigation();
    }

    public GuideItemSettings getItemSettings() {
        return itemSettings;
    }

    public String getDefaultLanguage() {
        return defaultLanguage;
    }
}
