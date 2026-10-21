package mcport;

import mcport.rt.BrowserRuntime;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Milestone 2: run Minecraft's real Bootstrap (version detection + all built-in registries). */
public final class M2Main {
    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            BrowserRuntime.report("stage", "failed");
            BrowserRuntime.report("error", BrowserRuntime.describe(t));
        }
    }

    private static void run() {
        BrowserRuntime.report("stage", "runtime-initialized");
        long t0 = System.currentTimeMillis();
        SharedConstants.tryDetectVersion();
        BrowserRuntime.report("version", SharedConstants.getCurrentVersion().name()
                + " protocol=" + SharedConstants.getProtocolVersion());
        Bootstrap.bootStrap();
        BrowserRuntime.report("bootstrap.ms", String.valueOf(System.currentTimeMillis() - t0));
        BrowserRuntime.report("registry.block", String.valueOf(BuiltInRegistries.BLOCK.size()));
        BrowserRuntime.report("registry.item", String.valueOf(BuiltInRegistries.ITEM.size()));
        BrowserRuntime.report("registry.entity_type", String.valueOf(BuiltInRegistries.ENTITY_TYPE.size()));
        int states = 0;
        for (Block b : BuiltInRegistries.BLOCK) {
            states += b.getStateDefinition().getPossibleStates().size();
        }
        BrowserRuntime.report("blockstates", String.valueOf(states));
        BrowserRuntime.report("Blocks.OAK_STAIRS.default", Blocks.OAK_STAIRS.defaultBlockState().toString());
        BrowserRuntime.report("stage", "m2-complete");
    }
}
