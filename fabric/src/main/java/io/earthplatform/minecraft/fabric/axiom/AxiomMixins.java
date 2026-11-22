package io.earthplatform.minecraft.fabric.axiom;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

/**
 * Applies the Axiom mixins only when Axiom is installed, and after they apply, rewrites Axiom's
 * calls that read the world's height and chunk sections directly into calls to {@link AxiomCubes},
 * which answers for cubic levels and passes everything else through. Axiom touches sections in
 * dozens of places, so one rewrite of each call beats a hook per place.
 */
public final class AxiomMixins implements IMixinConfigPlugin {
    private static final String BRIDGE = AxiomCubes.class.getName().replace('.', '/');

    /** Method name and descriptor (receiver left out), the receiver type the bridge takes, the bridge method, and the only owner to match (null: any). */
    private record Call(String name, String desc, String receiver, String bridge, String owner) {}

    private static final String HEIGHT = "net/minecraft/world/level/LevelHeightAccessor";
    private static final String CHUNK = "net/minecraft/world/level/chunk/LevelChunk";
    private static final String POS = "Lnet/minecraft/core/BlockPos;", STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String ENTITY = "Lnet/minecraft/world/level/block/entity/BlockEntity;";
    /** Since 26.1 the game runs with Mojang's names, so these are the names Axiom's calls carry. */
    private static final List<Call> CALLS = List.of(
            new Call("getMinY", "()I", HEIGHT, "minBuildHeight", null),
            new Call("getMaxY", "()I", HEIGHT, "maxY", null),
            new Call("getHeight", "()I", HEIGHT, "height", null),
            new Call("getMinSectionY", "()I", HEIGHT, "minSection", null),
            new Call("getMaxSectionY", "()I", HEIGHT, "maxSectionY", null),
            new Call("getSectionsCount", "()I", HEIGHT, "sectionsCount", null),
            new Call("getSectionIndex", "(I)I", HEIGHT, "sectionIndex", null),
            new Call("getSectionIndexFromSectionY", "(I)I", HEIGHT, "sectionIndexFromSectionY", null),
            new Call("getSectionYFromSectionIndex", "(I)I", HEIGHT, "sectionYFromSectionIndex", null),
            new Call("isOutsideBuildHeight", "(" + POS + ")Z", HEIGHT, "isOutsideBuildHeight", null),
            new Call("isOutsideBuildHeight", "(I)Z", HEIGHT, "isOutsideBuildHeight", null),
            new Call("getSection", "(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;", "net/minecraft/world/level/chunk/ChunkAccess", "getSection", null),
            new Call("setBlockState", "(III" + STATE + "Z)" + STATE, "net/minecraft/world/level/chunk/LevelChunkSection", "setBlockState", null),
            new Call("getBlockState", "(" + POS + ")" + STATE, CHUNK, "chunkBlockState", CHUNK),
            new Call("getBlockEntity", "(" + POS + "L" + CHUNK + "$EntityCreationType;)" + ENTITY, CHUNK, "chunkBlockEntity", null),
            new Call("removeBlockEntity", "(" + POS + ")V", CHUNK, "removeBlockEntity", CHUNK),
            new Call("setBlockEntity", "(" + ENTITY + ")V", CHUNK, "setBlockEntity", CHUNK),
            new Call("addAndRegisterBlockEntity", "(" + ENTITY + ")V", CHUNK, "addAndRegisterBlockEntity", null),
            new Call("update", "(III" + STATE + ")Z", "net/minecraft/world/level/levelgen/Heightmap", "updateHeightmap", "net/minecraft/world/level/levelgen/Heightmap"),
            new Call("update", "(Lnet/minecraft/world/level/BlockGetter;III)Z", "net/minecraft/world/level/lighting/ChunkSkyLightSources", "updateSkyLightSources",
                    "net/minecraft/world/level/lighting/ChunkSkyLightSources"),
            new Call("updateSectionStatus", "(Lnet/minecraft/core/SectionPos;Z)V", "net/minecraft/world/level/lighting/LevelLightEngine", "updateSectionStatus", null),
            // Axiom's own per-section light bookkeeping on ServerLevel, sized to the carrier's sections.
            new Call("axiom$getPendingLightUpdates", "(III)[S", "java/lang/Object", "pendingLightUpdates", "com/moulberry/axiom/hooks/ServerLevelExt"));

    @Override public void onLoad(String mixinPackage) {}

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return FabricLoader.getInstance().isModLoaded("axiom"); }

    @Override public void postApply(String targetClassName, ClassNode target, String mixinClassName, IMixinInfo mixinInfo) {
        if (!mixinClassName.endsWith(".AxiomTargetsMixin")) return;
        for (MethodNode method : target.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESTATIC || call.getOpcode() == Opcodes.INVOKESPECIAL) continue;
                for (Call c : CALLS) {
                    if (!c.name.equals(call.name) || !c.desc.equals(call.desc) || (c.owner != null ? !c.owner.equals(call.owner) : !fits(call.owner, c.receiver))) continue;
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.itf = false;
                    call.desc = "(L" + c.receiver + ";" + call.desc.substring(1);
                    call.owner = BRIDGE;
                    call.name = c.bridge;
                    break;
                }
            }
        }
    }

    /**
     * Whether a call's owner is the bridge's receiver type or one of its subtypes: with plain names, an
     * unrelated method of the same name and descriptor must stay as it is.
     */
    private static boolean fits(String owner, String receiver) {
        if (owner.equals(receiver)) return true;
        ClassInfo info = ClassInfo.forName(owner);
        return info != null && info.hasSuperClass(receiver, ClassInfo.Traversal.ALL, true);
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode target, String mixinClassName, IMixinInfo mixinInfo) {}
}
