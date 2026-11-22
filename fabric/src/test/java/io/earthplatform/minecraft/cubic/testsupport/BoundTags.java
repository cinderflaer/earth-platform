package io.earthplatform.minecraft.cubic.testsupport;

import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;

/**
 * Tag setup for unit tests, see {@link BoundTagsExtension}. The built-in registries are still open and their tags unbound
 * after bootstrap, where 1.21.1 let tests {@code bindTags} straight away: they are frozen here, as the game does before it loads tags.
 */
public final class BoundTags {
    private static boolean done;

    private BoundTags() {}

    /** Freezes every built-in registry and binds empty tags to it, once. */
    public static synchronized void bind() {
        if (done) return;
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        for (var registry : BuiltInRegistries.REGISTRY) {
            if (registry instanceof MappedRegistry<?> mapped) bindEmpty(mapped);
        }
        done = true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void bindEmpty(MappedRegistry registry) {
        registry.bindAllTagsToEmpty();
        registry.freeze();
        set(registry, Map.of());
    }

    /** Replaces the tags of a frozen registry, which {@code MappedRegistry.bindTags} no longer does. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> void set(Registry<T> registry, Map<TagKey<T>, List<Holder<T>>> tags) {
        ((MappedRegistry) registry).prepareTagReload(new TagLoader.LoadResult(registry.key(), tags)).apply();
    }

    /** The tags a registry holds now, to put back with {@link #set}. */
    public static <T> Map<TagKey<T>, List<Holder<T>>> get(Registry<T> registry) {
        bind();
        return registry.getTags().collect(java.util.stream.Collectors.toMap(named -> named.key(), named -> named.stream().toList()));
    }
}
