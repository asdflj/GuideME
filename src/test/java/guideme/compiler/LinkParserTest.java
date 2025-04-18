package guideme.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import guideme.PageAnchor;
import guideme.PageCollection;
import guideme.extensions.ExtensionCollection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@MockitoSettings
class LinkParserTest {
    private final List<PageAnchor> anchors = new ArrayList<>();
    private final List<URI> external = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    private final LinkParser.Visitor visitor = new LinkParser.Visitor() {
        @Override
        public void handlePage(PageAnchor page) {
            anchors.add(page);
        }

        @Override
        public void handleExternal(URI uri) {
            external.add(uri);
        }

        @Override
        public void handleError(String error) {
            errors.add(error);
        }
    };
    private PageCompiler compiler;

    @BeforeEach
    void setUp() {
        var pageCollection = mock(PageCollection.class, withSettings().strictness(Strictness.LENIENT));
        when(pageCollection.pageExists(ResourceLocation.parse("ns:other/page.md"))).thenReturn(true);
        when(pageCollection.pageExists(ResourceLocation.parse("ns2:abc/def.md"))).thenReturn(true);
        when(pageCollection.pageExists(ResourceLocation.parse("ns_2:abc/def.md"))).thenReturn(true);
        compiler = new PageCompiler(pageCollection, ExtensionCollection.empty(), "pack",
                ResourceLocation.parse("ns:subfolder/page.md"), "");
    }

    @Test
    void testRelativeLink() {
        LinkParser.parseLink(compiler, "../other/page.md", visitor);
        assertThat(external).containsExactly();
        assertThat(errors).containsExactly();
        assertThat(anchors).containsExactly(PageAnchor.page(ResourceLocation.parse("ns:other/page.md")));
    }

    @Test
    void testRelativeLinkWithFragment() {
        LinkParser.parseLink(compiler, "../other/page.md#fragment", visitor);
        assertThat(external).containsExactly();
        assertThat(errors).containsExactly();
        assertThat(anchors).containsExactly(new PageAnchor(ResourceLocation.parse("ns:other/page.md"), "fragment"));
    }

    @Test
    void testAbsoluteLink() {
        LinkParser.parseLink(compiler, "/other/page.md", visitor);
        assertThat(external).containsExactly();
        assertThat(errors).containsExactly();
        assertThat(anchors).containsExactly(PageAnchor.page(ResourceLocation.parse("ns:other/page.md")));
    }

    @Test
    void testAbsoluteLinkWithFragment() {
        LinkParser.parseLink(compiler, "/other/page.md#fragment", visitor);
        assertThat(external).containsExactly();
        assertThat(errors).containsExactly();
        assertThat(anchors).containsExactly(new PageAnchor(ResourceLocation.parse("ns:other/page.md"), "fragment"));
    }

    @Test
    void testLinkToOtherNamespace() {
        LinkParser.parseLink(compiler, "ns2:abc/def.md", visitor);
        assertThat(external).containsExactly();
        assertThat(errors).containsExactly();
        assertThat(anchors).containsExactly(PageAnchor.page(ResourceLocation.parse("ns2:abc/def.md")));
    }

    // This failed previously due to URI rules not allowing a scheme with _
    @Test
    void testLinkToOtherNamespaceWithUnderscore() {
        LinkParser.parseLink(compiler, "ns_2:abc/def.md", visitor);
        assertThat(external).containsExactly();
        assertThat(errors).containsExactly();
        assertThat(anchors).containsExactly(PageAnchor.page(ResourceLocation.parse("ns_2:abc/def.md")));
    }

    @Test
    void testLinkToOtherNamespaceWithFragment() {
        LinkParser.parseLink(compiler, "ns2:abc/def.md#fragment", visitor);
        assertThat(external).containsExactly();
        assertThat(errors).containsExactly();
        assertThat(anchors).containsExactly(new PageAnchor(ResourceLocation.parse("ns2:abc/def.md"), "fragment"));
    }
}
