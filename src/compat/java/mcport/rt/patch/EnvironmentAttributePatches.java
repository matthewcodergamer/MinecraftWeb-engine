package mcport.rt.patch;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.Vec3;

/**
 * Browser-side cache for the hottest positional environment attribute.
 *
 * <p>{@code ClientLevel.doAnimateTick} samples 1,334 random block positions per
 * client tick and asks for {@code AMBIENT_PARTICLES} at each one. The vanilla
 * {@link EnvironmentAttributeReader#getValue(EnvironmentAttribute, BlockPos)}
 * default resolves the biome and evaluates the attribute layers every time.
 * On the Wasm-GC client that was 9.2% of all sampled main-thread time, plus the
 * biome palette lookups underneath it.</p>
 *
 * <p>Ambient particles are a biome property: the value is constant inside a
 * 4x4x4-block biome cell and does not change with time. Cache it per quart cell
 * for about one second. A one-second refresh bounds any stale value after a
 * datapack/resource reload while still turning ~26,000 lookups per second into
 * a few thousand misses on the first tick and near-100% hits afterwards.</p>
 *
 * <p>Every other attribute (and every other reader) goes straight to the
 * original three-argument implementation, so this patch cannot change their
 * semantics.</p>
 */
public final class EnvironmentAttributePatches {
    private EnvironmentAttributePatches() {
    }

    /** Four thousand cells cover the 64-block cube sampled by animateTick. */
    private static final int MAX_ENTRIES = 8192;
    /** One-second buckets: ambient particles do not change within a tick. */
    private static final int BUCKET_SHIFT = 10;

    private static EnvironmentAttributeReader cachedReader;
    private static Long2ObjectOpenHashMap<Object> cache;
    private static long cachedBucket = Long.MIN_VALUE;
    private static int calls;

    /**
     * Replacement body for
     * {@code EnvironmentAttributeReader.getValue(EnvironmentAttribute, BlockPos)}.
     */
    @SuppressWarnings("unchecked")
    public static <Value> Value getValue(EnvironmentAttributeReader reader,
            EnvironmentAttribute<Value> attribute, BlockPos pos) {
        // Identity comparison through Object avoids a generic-cast error between
        // EnvironmentAttribute<Value> and the List<AmbientParticle> field type.
        if ((Object) attribute != (Object) EnvironmentAttributes.AMBIENT_PARTICLES) {
            return reader.getValue(attribute, Vec3.atCenterOf(pos), null);
        }

        // currentTimeMillis is a JS boundary crossing in the browser. Check the
        // one-second bucket once per 256 lookups instead of on every one of the
        // ~26,000 calls per second; the worst-case staleness is ~0.8 tick, far
        // below the one-second bucket itself.
        if (reader != cachedReader || ((++calls & 255) == 0
                && (System.currentTimeMillis() >> BUCKET_SHIFT) != cachedBucket)) {
            cachedReader = reader;
            cachedBucket = System.currentTimeMillis() >> BUCKET_SHIFT;
            if (cache == null) {
                cache = new Long2ObjectOpenHashMap<>(2048);
            } else {
                cache.clear();
            }
        }

        // 24 bits of quart X, 24 bits of quart Z, 8 bits of quart Y. The world
        // border is +/-30M blocks, so 24 signed quart bits are sufficient.
        long key = ((long) (pos.getX() >> 2) & 0xFFFFFFL)
                | (((long) (pos.getZ() >> 2) & 0xFFFFFFL) << 24)
                | (((long) (pos.getY() >> 2) & 0xFFL) << 48);
        Object cached = cache.get(key);
        if (cached != null) {
            return (Value) cached;
        }

        Value value = reader.getValue(attribute, Vec3.atCenterOf(pos), null);
        if (value != null) {
            if (cache.size() >= MAX_ENTRIES) {
                cache.clear();
            }
            cache.put(key, value);
        }
        return value;
    }
}
