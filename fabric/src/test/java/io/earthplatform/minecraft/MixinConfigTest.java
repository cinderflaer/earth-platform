package io.earthplatform.minecraft;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Mixin reserves each config's package, so no config may claim one that holds ordinary classes. */
class MixinConfigTest {
    private static final Set<String> CONFIGS = Set.of("earthplatform.cubic.mixins.json", "earthplatform.fabric.mixins.json", "earthplatform.axiom.mixins.json");

    @Test void metadataRegistersEachConfigOnce() throws Exception {
        String project = System.getProperty("earthplatform.test.projectDirectory");
        assertNotNull(project, "Explicit test project directory is required");
        String template = Files.readString(Path.of(project, "src/main/resources/fabric.mod.json"));
        var values = new ArrayList<String>();
        for (var entry : JsonParser.parseString(template).getAsJsonObject().getAsJsonArray("mixins"))
            values.add(entry.getAsString().replace("${mod_id}", "earthplatform"));
        assertEquals(CONFIGS.size(), values.size());
        assertEquals(CONFIGS, Set.copyOf(values));
    }

    @Test void noConfigReservesApplicationPackages() throws Exception {
        var applicationClasses = List.of("io.earthplatform.minecraft.cubic.compat.CubicCompat", "io.earthplatform.minecraft.EarthPlatformMod",
                "io.earthplatform.minecraft.fabric.EarthPlatformFabric");
        for (String name : CONFIGS) {
            try (var input = getClass().getClassLoader().getResourceAsStream(name)) {
                assertNotNull(input, name);
                String prefix = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject().get("package").getAsString() + ".";
                for (String application : applicationClasses)
                    assertFalse(application.startsWith(prefix), name + " reserves application class " + application);
            }
        }
    }
}
