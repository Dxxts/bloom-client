package com.bloomclient.engine.compatibility;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.client.resources.model.SimpleModelWrapper;

/**
 * Registry of model classes whose geometry the native mesher may consume.
 *
 * <ul>
 *   <li><b>Automatic adapter:</b> vanilla's {@link SingleVariant},
 *   {@link WeightedVariants}, {@link MultiPartModel} and
 *   {@link SimpleModelWrapper}. Their {@code collectParts}/{@code getQuads}
 *   depend only on the state and the seeded random, which Java reproduces
 *   exactly. Exact class match: subclasses from mods are not trusted.</li>
 *   <li><b>Native adapter:</b> a mod whose model classes satisfy the same
 *   contract registers them with {@link #registerStaticModel} /
 *   {@link #registerStaticPart} during client initialisation.</li>
 *   <li><b>Java fallback:</b> every other class is tesselated by the vanilla
 *   renderer, block by block.</li>
 * </ul>
 *
 * <p>The contract: {@code collectParts(random, out)} reads nothing but the
 * random source, and {@code getQuads(direction)} returns the same immutable
 * quads on every call.
 */
public final class BloomRenderAdapters {
	private static final Set<Class<?>> STATIC_MODELS = ConcurrentHashMap.newKeySet();
	private static final Set<Class<?>> STATIC_PARTS = ConcurrentHashMap.newKeySet();

	static {
		STATIC_MODELS.add(SingleVariant.class);
		STATIC_MODELS.add(WeightedVariants.class);
		STATIC_MODELS.add(MultiPartModel.class);
		STATIC_PARTS.add(SimpleModelWrapper.class);
	}

	private BloomRenderAdapters() {
	}

	/** Native adapter API. Takes effect at the next resource reload. */
	public static void registerStaticModel(Class<? extends BlockStateModel> type) {
		STATIC_MODELS.add(type);
	}

	/** Native adapter API. Takes effect immediately for new model combinations. */
	public static void registerStaticPart(Class<? extends BlockStateModelPart> type) {
		STATIC_PARTS.add(type);
	}

	public static boolean isStaticModel(BlockStateModel model) {
		return STATIC_MODELS.contains(model.getClass());
	}

	public static boolean isStaticPart(BlockStateModelPart part) {
		return STATIC_PARTS.contains(part.getClass());
	}
}
