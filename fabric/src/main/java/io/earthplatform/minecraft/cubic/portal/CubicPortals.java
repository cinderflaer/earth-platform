package io.earthplatform.minecraft.cubic.portal;

import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.earthplatform.minecraft.cubic.mixin.NetherPortalAccess;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Nether portals of the cubic Earth. The Nether and the End stay vanilla dimensions; a player
 * who left the cubic world for them comes back to it, everyone else to the vanilla overworld.
 * Vanilla finds portals through the overworld's point-of-interest store, which only spans the
 * carrier's few blocks of height, so the cubic world keeps its own list of portal blocks and
 * builds new portals on the real ground.
 */
public final class CubicPortals {
    /** Player tag: this player's overworld is the cubic world. Tags are saved and survive death. */
    public static final String HOME_TAG = "earthplatform.cubic_home";
    /** Vanilla's overworld search radius for a portal arriving from the Nether. */
    private static final int SEARCH = 128;
    /**
     * A wider search before building a new portal. The Nether side of a portal can stand up to 16
     * Nether blocks (128 here) from where the Earth portal maps to, so the way back may land just
     * past {@link #SEARCH}; building a second portal there would strand the first one on real
     * terrain, often far up or down a slope.
     */
    private static final int LINK_SEARCH = SEARCH + 16 * 8;
    /** How far from the target a new portal may move to find level ground. */
    private static final int BUILD_SEARCH = 16;

    /** The Nether's lava sea level, the Nether height that is anchored to the Earth's sea level. */
    public static final int NETHER_ANCHOR_Y = NetherHeight.ANCHOR_Y;
    /** Lowest and highest Nether Y a mapped portal may be sought or built at (inside bedrock, below the roof). */
    public static final int NETHER_MIN_Y = NetherHeight.NETHER_MIN_Y, NETHER_MAX_Y = NetherHeight.NETHER_MAX_Y;
    /**
     * Earth blocks per Nether block of height. The Earth is 32768 tall but the Nether only 256, so
     * the portal mapping stretches Y: toEarthY(y) = SEA + (y - 32) * factor. With the default of 8
     * (the same ratio as X and Z, so a portal moves along a straight line through the worlds), the
     * Nether's band 1..120 covers Earth Y -186..518 and the lava sea (32) is the Earth's sea level.
     * Override with the system property {@code earthplatform.netherYScale}.
     */
    public static final double Y_FACTOR = factor();

    private CubicPortals() {}

    private static double factor() {
        try {
            double value = Double.parseDouble(System.getProperty("earthplatform.netherYScale", "8"));
            return value > 0 && Double.isFinite(value) ? value : 8;
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    /** The Earth Y for a Nether Y, linear around the anchors and clamped to the cubic world's range. */
    public static double toEarthY(double netherY) {
        return toEarthY(netherY, Y_FACTOR);
    }

    public static double toEarthY(double netherY, double factor) {
        return NetherHeight.toEarthY(netherY, factor);
    }

    /** The Nether Y for an Earth Y, the inverse of {@link #toEarthY}, clamped to the Nether's buildable band. */
    public static double toNetherY(double earthY) {
        return toNetherY(earthY, Y_FACTOR);
    }

    public static double toNetherY(double earthY, double factor) {
        return NetherHeight.toNetherY(earthY, factor);
    }

    public static boolean livesInCubic(ServerPlayer player) {
        return Cubic.isCubic(player.level().dimension()) || player.entityTags().contains(HOME_TAG);
    }

    @Nullable public static ServerLevel cubicLevel(ServerLevel any) {
        ServerLevel level = any.getServer().getLevel(Cubic.LEVEL);
        return level != null && CubicLevel.of(level) instanceof ServerCubeWorld ? level : null;
    }

    /**
     * Where a Nether portal leads an entity standing in it, when that is the cubic world: always
     * for players who live there, and for anything else when the portal was built from it.
     */
    @Nullable public static net.minecraft.world.level.portal.TeleportTransition fromNether(ServerLevel nether, Entity entity, BlockPos portal) {
        ServerLevel cubic = cubicLevel(nether);
        if (cubic == null || !(CubicLevel.of(cubic) instanceof ServerCubeWorld cubes)) return null;
        double scale = DimensionType.getTeleportationScale(nether.dimensionType(), cubic.dimensionType());
        BlockPos target = cubic.getWorldBorder().clampToBounds(entity.getX() * scale, toEarthY(entity.getY()), entity.getZ() * scale);
        Registry registry = Registry.of(cubic);
        BlockPos found = nearest(cubes, registry, target, SEARCH);
        if (found == null) found = nearest(cubes, registry, target, LINK_SEARCH);
        boolean home = entity instanceof ServerPlayer player && player.entityTags().contains(HOME_TAG);
        if (found == null && !home) return null;

        BlockUtil.FoundRectangle exit;
        if (found != null) {
            BlockState state = cubes.getBlockState(found);
            exit = BlockUtil.getLargestRectangleAround(found, state.getValue(NetherPortalBlock.AXIS), 21, Direction.Axis.Y, 21,
                    pos -> cubes.getBlockState(pos) == state);
        } else {
            Direction.Axis axis = nether.getBlockState(portal).getOptionalValue(NetherPortalBlock.AXIS).orElse(Direction.Axis.X);
            exit = build(cubic, cubes, target.getX(), target.getY(), target.getZ(), axis);
        }
        return NetherPortalAccess.earthplatform$exit(entity, portal, exit, cubic,
                net.minecraft.world.level.portal.TeleportTransition.PLAY_PORTAL_SOUND);
    }

    /** The registered portal block closest to a target column within a square, checked against the real block. */
    @Nullable private static BlockPos nearest(ServerCubeWorld cubes, Registry registry, BlockPos target, int search) {
        while (true) {
            BlockPos best = null;
            long bestDistance = Long.MAX_VALUE;
            for (BlockPos pos : registry.portals) {
                long dx = pos.getX() - target.getX(), dz = pos.getZ() - target.getZ();
                long dy = pos.getY() - (long) target.getY();
                if (Math.abs(dx) > search || Math.abs(dz) > search || Math.abs(dy) > search) continue;
                // Prefer the nearest block in 3D, then the lowest, so the rectangle grows from its bottom.
                long distance = (dx * dx + dz * dz + dy * dy) * 65536L + (pos.getY() - (long) CubeWorld.MIN_Y);
                if (distance < bestDistance) { bestDistance = distance; best = pos; }
            }
            if (best == null) return null;
            cubes.loadAround(best);
            if (cubes.getBlockState(best).is(Blocks.NETHER_PORTAL)) return best;
            registry.remove(best);
        }
    }

    /**
     * Builds a portal near a column: on the nearest level dry ground with room to step out, else
     * on an obsidian platform at the target, clearing the space around it like vanilla does. When
     * the mapped height {@code y} is in clear air well above the terrain (and its trees) the portal
     * hangs there on an obsidian platform, keeping the Nether height; at or below the surface the
     * portal goes on the ground instead.
     */
    private static BlockUtil.FoundRectangle build(ServerLevel level, ServerCubeWorld cubes, int x, int y, int z, Direction.Axis axis) {
        CubeGenerator generator = cubes.generator();
        int ground = Math.max(generator.surfaceY(x, z), generator.waterY(x, z));
        boolean aloft = y > ground + 8 && y > cubes.skyTop(x, z) + 8 && CubeWorld.inRange(y + 8);
        if (aloft) ground = y - 1;
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) for (int dy = -1; dy <= 1; dy++) {
            cubes.loadNow((x >> 4) + dx, (ground >> 4) + dy, (z >> 4) + dz);
        }
        Direction along = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Direction across = along.getClockWise();
        BlockPos spot = null;
        for (int radius = 0; radius <= BUILD_SEARCH && spot == null && !aloft; radius++) {
            for (int dz = -radius; dz <= radius && spot == null; dz++) for (int dx = -radius; dx <= radius; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                BlockPos base = new BlockPos(x + dx, ground(cubes, x + dx, z + dz) + 1, z + dz);
                if (fits(cubes, base, along, across)) { spot = base; break; }
            }
        }
        if (spot == null) {
            spot = new BlockPos(x, ground + 1, z);
            for (int i = -1; i <= 2; i++) for (int j = -1; j <= 1; j++) {
                BlockPos column = spot.relative(along, i).relative(across, j);
                level.setBlock(column.below(), Blocks.OBSIDIAN.defaultBlockState(), 3);
                for (int k = 0; k < 4; k++) level.setBlock(column.above(k), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        for (int i = -1; i <= 2; i++) for (int k = -1; k <= 3; k++) {
            if (i == -1 || i == 2 || k == -1 || k == 3) level.setBlock(spot.relative(along, i).above(k), Blocks.OBSIDIAN.defaultBlockState(), 3);
        }
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, axis);
        for (int i = 0; i <= 1; i++) for (int k = 0; k <= 2; k++) level.setBlock(spot.relative(along, i).above(k), portal, 18);
        return new BlockUtil.FoundRectangle(spot, 2, 3);
    }

    /** The top ground block of a column, below any tree canopy standing on it. */
    private static int ground(ServerCubeWorld cubes, int x, int z) {
        int y = cubes.skyTop(x, z), floor = y - 48;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
        while (y > floor) {
            BlockState state = cubes.getBlockState(pos.setY(y));
            if (!(state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty())) break;
            y--;
        }
        return y;
    }

    /** Solid ground under a 4 by 3 footprint, open air above it for the frame and to step out. */
    private static boolean fits(ServerCubeWorld cubes, BlockPos base, Direction along, Direction across) {
        Level level = cubes.level();
        for (int i = -1; i <= 2; i++) for (int j = -1; j <= 1; j++) {
            BlockPos column = base.relative(along, i).relative(across, j);
            BlockPos below = column.below();
            BlockState floor = cubes.getBlockState(below);
            if (!cubes.isLoaded(below) || !floor.getFluidState().isEmpty() || !floor.isFaceSturdy(level, below, Direction.UP)) return false;
            for (int k = 0; k < 4; k++) {
                BlockState above = cubes.getBlockState(column.above(k));
                if (!above.isAir() && !(above.canBeReplaced() && above.getFluidState().isEmpty())) return false;
            }
            if (!cubes.isLoaded(column.above(4))) return false;
        }
        return true;
    }

    /** Keeps the portal list in step with the cubic world's blocks. */
    public static void blockChanged(ServerLevel cubic, BlockPos pos, BlockState old, BlockState now) {
        boolean was = old.is(Blocks.NETHER_PORTAL), is = now.is(Blocks.NETHER_PORTAL);
        if (was == is) return;
        Registry registry = Registry.of(cubic);
        if (is) registry.add(pos.immutable()); else registry.remove(pos);
    }

    /** Every Nether portal block in the cubic world, saved with the dimension. */
    static final class Registry extends SavedData {
        private static final String NAME = "earthplatform_cubic_portals";
        /** How this is saved with the world: as one compound, under a name of its own. */
        private static final net.minecraft.world.level.saveddata.SavedDataType<Registry> TYPE = new net.minecraft.world.level.saveddata.SavedDataType<>(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("earthplatform", "cubic_portals"), Registry::new,
                net.minecraft.nbt.CompoundTag.CODEC.xmap(tag -> load(tag, null), data -> data.save(new net.minecraft.nbt.CompoundTag(), null)), null);
        final Set<BlockPos> portals = new HashSet<>();

        static Registry of(ServerLevel cubic) {
            return cubic.getDataStorage().computeIfAbsent(TYPE);
        }

        private static Registry load(CompoundTag tag, HolderLookup.Provider registries) {
            Registry registry = new Registry();
            int[] packed = tag.getIntArray("portals").orElse(new int[0]);
            for (int i = 0; i + 2 < packed.length; i += 3) registry.portals.add(new BlockPos(packed[i], packed[i + 1], packed[i + 2]));
            return registry;
        }

        void add(BlockPos pos) { if (portals.add(pos)) setDirty(); }
        void remove(BlockPos pos) { if (portals.remove(pos)) setDirty(); }

        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            int[] packed = new int[portals.size() * 3];
            int i = 0;
            for (BlockPos pos : portals) { packed[i++] = pos.getX(); packed[i++] = pos.getY(); packed[i++] = pos.getZ(); }
            tag.putIntArray("portals", packed);
            return tag;
        }
    }
}
