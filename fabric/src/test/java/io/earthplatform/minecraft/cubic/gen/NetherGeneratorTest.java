package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The tall cubic Nether: solid ends without bedrock, lava seas and caverns in every storey, deterministic. */
class NetherGeneratorTest {
    private static NetherGenerator generator;

    @BeforeAll static void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements()
                .forEach(holder -> registry.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        Registry<Biome> biomes = registry.freeze();
        generator = new NetherGenerator(12345L, biomes);
    }

    private static int count(LevelChunkSection section, BlockState state) {
        int n = 0;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) if (section.getBlockState(x, y, z) == state) n++;
        return n;
    }

    @Test void solidNetherrackBeyondTheRange() {
        for (int cy : new int[]{(NetherGenerator.MIN_Y >> 4) - 5, (NetherGenerator.MAX_Y >> 4) + 5, -1024, 1023}) {
            LevelChunkSection section = generator.generate(3, cy, -7);
            assertEquals(4096, count(section, Blocks.NETHERRACK.defaultBlockState()), "cube y " + cy);
        }
    }

    @Test void noBedrockAnywhere() {
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        for (int cy = (NetherGenerator.MIN_Y >> 4) - 2; cy <= (NetherGenerator.MIN_Y >> 4) + 2; cy++)
            assertEquals(0, count(generator.generate(0, cy, 0), bedrock));
        for (int cy = -8; cy <= 8; cy++) assertEquals(0, count(generator.generate(5, cy, 5), bedrock));
    }

    @Test void deterministic() {
        LevelChunkSection a = generator.generate(2, 3, 4), b = generator.generate(2, 3, 4);
        for (int i = 0; i < 4096; i++)
            assertEquals(a.getBlockState(i & 15, i >> 8, i >> 4 & 15), b.getBlockState(i & 15, i >> 8, i >> 4 & 15));
    }

    /** Every storey, high or low, has air, rock and a lava sea, so the Nether is as deep as it is tall. */
    @Test void everyStoreyHasCavernsAndLava() {
        for (int storey : new int[]{-12, -3, 0, 1, 9, 14}) {
            int base = storey * NetherGenerator.PERIOD, air = 0, lava = 0, rock = 0;
            for (int cx = 0; cx < 6; cx++) for (int cz = 0; cz < 6; cz++) for (int dy = 0; dy < 8; dy++) {
                LevelChunkSection section = generator.generate(cx, (base >> 4) + dy, cz);
                air += count(section, Blocks.AIR.defaultBlockState());
                lava += count(section, Blocks.LAVA.defaultBlockState());
                rock += count(section, Blocks.NETHERRACK.defaultBlockState());
            }
            assertTrue(air > 10_000, "air in storey " + storey);
            assertTrue(lava > 1_000, "lava in storey " + storey);
            assertTrue(rock > 10_000, "rock in storey " + storey);
        }
    }

    @Test void lavaOnlyBelowItsStoreysSea() {
        for (int cy = 0; cy < 8; cy++) {
            LevelChunkSection section = generator.generate(1, cy, 1);
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                if (section.getBlockState(x, y, z) == Blocks.LAVA.defaultBlockState())
                    assertTrue(cy * 16 + y <= NetherGenerator.lavaLevel(cy * 16 + y), "lava above its sea at y " + (cy * 16 + y));
        }
    }

    @Test void lavaSeaOfTheFirstStoreyIsAtTheVanillaLevel() {
        assertEquals(32, NetherGenerator.lavaLevel(0));
        assertEquals(32, NetherGenerator.lavaLevel(127));
        assertEquals(-96, NetherGenerator.lavaLevel(-1));
        assertEquals(32, generator.seaLevel());
    }

    @Test void hasNoFarTerrainAndShadesTheWholeWorld() {
        assertFalse(generator.farTerrain());
        assertEquals(16383, generator.skyTopY(0, 0));
    }
}
