package io.earthplatform.minecraft.cubic.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.serialization.Lifecycle;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Join and respawn packets must keep the vanilla layout, which Replay Mod rewrites every recording's join packet by. */
class SpawnInfoWireTest {
    private static final ResourceKey<Level> CUBIC = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("earthplatform", "cubic"));
    private static RegistryAccess access;
    private static Holder<DimensionType> type;

    @BeforeAll static void registries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        MappedRegistry<DimensionType> types = new MappedRegistry<>(Registries.DIMENSION_TYPE, Lifecycle.stable());
        type = Registry.registerForHolder(types, BuiltinDimensionTypes.OVERWORLD,
                net.minecraft.data.registries.VanillaRegistries.createLookup().lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value());
        types.freeze();
        access = new RegistryAccess.ImmutableRegistryAccess(List.of(types));
    }

    private static RegistryFriendlyByteBuf write(BlockPos death) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        new CommonPlayerSpawnInfo(type, CUBIC, 42L, GameType.SURVIVAL, null, false, true, Optional.of(GlobalPos.of(CUBIC, death)), 0, 63).write(buffer);
        return buffer;
    }

    /** Reads the bytes the way a vanilla-layout reader does (Replay Mod's ReplayStudio), and returns the death spot. */
    private static BlockPos readVanilla(RegistryFriendlyByteBuf buffer) {
        buffer.readVarInt();
        buffer.readResourceKey(Registries.DIMENSION);
        buffer.readLong();
        buffer.readByte();
        buffer.readByte();
        buffer.readBoolean();
        buffer.readBoolean();
        buffer.readBoolean();
        buffer.readResourceKey(Registries.DIMENSION);
        BlockPos death = BlockPos.of(buffer.readLong());
        buffer.readVarInt();
        buffer.readVarInt();
        assertEquals(0, buffer.readableBytes(), "bytes past the vanilla layout");
        return death;
    }

    @Test void aDeathOnAHighMountainKeepsTheVanillaLayout() {
        BlockPos death = readVanilla(write(new BlockPos(12214958, 3832, -3332340)));
        assertEquals(new BlockPos(12214958, 2047, -3332340), death);
    }

    @Test void aDeathFarBelowKeepsTheVanillaLayout() {
        assertEquals(new BlockPos(5, -2047, 9), readVanilla(write(new BlockPos(5, -9000, 9))));
    }

    @Test void aDeathInTheVanillaRangeIsUnchanged() {
        assertEquals(new BlockPos(624905, 156, -7027670), readVanilla(write(new BlockPos(624905, 156, -7027670))));
    }

    @Test void theGameReadsItsOwnJoinPacketBack() {
        var info = new CommonPlayerSpawnInfo(write(new BlockPos(1, 3832, 2)));
        assertEquals(new BlockPos(1, 2047, 2), info.lastDeathLocation().orElseThrow().pos());
    }

    @Test void otherPositionsStillCarryTheirTallY() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBlockPos(new BlockPos(1, 3832, 2));
        assertEquals(12, buffer.readableBytes());
        assertEquals(new BlockPos(1, 3832, 2), buffer.readBlockPos());
    }
}
