package mcport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import mcport.rt.BrowserRuntime;

/**
 * Milestone 1: execute unmodified Minecraft 26.3 client bytecode in the browser.
 * Every value below is computed by Mojang's own classes, compiled by TeaVM.
 */
public final class M1Main {
    private static void report(String k, String v) {
        BrowserRuntime.report(k, v);
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            report("stage", "failed");
            report("error", BrowserRuntime.describe(t));
        }
    }

    private static void run() {
        report("stage", "runtime-initialized");
        report("Mth.sin(PI/2)", String.valueOf(Mth.sin(Math.PI / 2)));
        report("Mth.wrapDegrees(370f)", String.valueOf(Mth.wrapDegrees(370f)));
        report("Mth.hsvToRgb(0.5,1,1)", Integer.toHexString(Mth.hsvToRgb(0.5f, 1f, 1f)));
        BlockPos pos = new BlockPos(10, 64, -5).relative(Direction.NORTH, 3).above();
        report("BlockPos", pos.toShortString() + " asLong=" + pos.asLong());
        report("BlockPos.of(asLong)", BlockPos.of(pos.asLong()).toShortString());
        AABB box = new AABB(0, 0, 0, 1, 1, 1).inflate(0.5).move(new Vec3(2, 0, 0));
        report("AABB", box.toString());
        Identifier id = Identifier.withDefaultNamespace("stone");
        report("Identifier", id + " path=" + id.getPath());
        report("stage", "m1-complete");
    }
}
