package io.earthplatform.minecraft.cubic.testsupport;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Minecraft 26.2 throws "Tags not bound" for any tag query on a registry entry whose tags no data pack has bound,
 * where 1.21.1 answered "not in the tag". Unit tests load no data packs, so every built-in registry gets empty tags once.
 * The extension is created outside the Fabric class loader, so it reaches {@link BoundTags} through the test class's loader,
 * which is the one that holds the Minecraft classes the tests use.
 */
public final class BoundTagsExtension implements BeforeAllCallback {
    @Override public void beforeAll(ExtensionContext context) throws Exception {
        ClassLoader loader = context.getRequiredTestClass().getClassLoader();
        Class.forName(BoundTags.class.getName(), true, loader).getMethod("bind").invoke(null);
    }
}
