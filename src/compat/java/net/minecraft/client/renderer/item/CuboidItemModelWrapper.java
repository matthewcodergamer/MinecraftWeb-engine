package net.minecraft.client.renderer.item;

import com.google.common.base.Suppliers;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;

/**
 * TeaVM compatibility overlay for Minecraft 26.3.
 *
 * Keeps the original wrapper's constructor and rendering/update behavior.
 * computeExtents avoids Set.toArray(IntFunction), whose generated array
 * factory was a suspect in the Wasm-GC nullability investigation.
 */
public class CuboidItemModelWrapper implements ItemModel {
    private final List<ItemTintSource> tints;
    private final boolean animated;
    private final ItemQuads itemQuads;
    private final Supplier<Vector3fc[]> extents;
    private final ModelRenderProperties properties;
    private final Matrix4fc transformation;

    // Public rather than private so the separately compiled overlay remains
    // accessible to Minecraft's precompiled nested Unbaked class.
    public CuboidItemModelWrapper(
            List<ItemTintSource> tints,
            QuadCollection quads,
            ModelRenderProperties properties,
            Matrix4fc transformation) {
        this.tints = tints;
        this.animated = quads.hasMaterialFlag(2);
        this.itemQuads = ItemQuads.split(quads.getAll());
        this.properties = properties;
        this.transformation = transformation;
        this.extents = Suppliers.memoize(() -> computeExtents(quads.getAll()));
    }

    public static Vector3fc[] computeExtents(List<BakedQuad> quads) {
        Set<Vector3fc> unique = new HashSet<>();
        for (BakedQuad quad : quads) {
            for (int vertex = 0; vertex < 4; vertex++) {
                Vector3fc position = quad.position(vertex);
                if (position != null) {
                    unique.add(position);
                }
            }
        }

        Vector3fc[] result = new Vector3fc[unique.size()];
        int index = 0;
        for (Vector3fc position : unique) {
            result[index++] = position;
        }
        return result;
    }

    @Override
    public void update(
            ItemStackRenderState state,
            ItemStack stack,
            ItemModelResolver resolver,
            ItemDisplayContext displayContext,
            ClientLevel level,
            ItemOwner owner,
            int seed) {
        state.appendModelIdentityElement(this);
        ItemStackRenderState.LayerRenderState layer = state.newLayer();

        if (stack.hasFoil()) {
            ItemStackRenderState.FoilType foilType =
                    hasSpecialAnimatedTexture(stack)
                            ? ItemStackRenderState.FoilType.SPECIAL
                            : ItemStackRenderState.FoilType.STANDARD;
            layer.setFoilType(foilType);
            state.setAnimated();
            state.appendModelIdentityElement(foilType);
        }

        if (!tints.isEmpty()) {
            var tintLayers = layer.tintLayers();
            for (ItemTintSource tintSource : tints) {
                // Keep the null and non-null calls separate: do not merge a
                // nullable LivingEntity reference at the call site.
                int tint;
                if (owner == null) {
                    tint = tintSource.calculate(stack, level, null);
                } else {
                    LivingEntity livingOwner = owner.asLivingEntity();
                    tint = tintSource.calculate(stack, level, livingOwner);
                }
                tintLayers.add(tint);
                state.appendModelIdentityElement(Integer.valueOf(tint));
            }
        }

        layer.setExtents(extents);
        layer.setLocalTransform(transformation);
        properties.applyToLayer(layer, displayContext);
        layer.setQuads(itemQuads);
        if (animated) {
            state.setAnimated();
        }
    }

    private static boolean hasSpecialAnimatedTexture(ItemStack stack) {
        return stack.is(ItemTags.COMPASSES) || stack.is((Object) Items.CLOCK);
    }
}
