package guideme.internal.siteexport.model;

import guideme.libs.mdast.model.MdAstRoot;
import java.util.HashMap;
import java.util.Map;

public class ExportedPageJson {
    public String title;
    public MdAstRoot astRoot;

    public Map<String, Object> frontmatter = new HashMap<>();
}
