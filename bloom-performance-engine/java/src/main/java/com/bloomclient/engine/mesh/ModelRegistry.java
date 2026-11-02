package com.bloomclient.engine.mesh;

import com.bloomclient.engine.BloomEngine;
import com.bloomclient.engine.bridge.NativeBridge;
import com.bloomclient.engine.bridge.NativeLibrary;
import com.bloomclient.engine.bridge.Protocol;
import com.bloomclient.engine.compatibility.FabricApiAdapter;
import com.bloomclient.engine.compatibility.BloomRenderAdapters;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

/**
 * Native ids for combinations of model parts. A combination is what
 * {@code BlockStateModel.collectParts} returns for one block; its quads are
 * serialized once, in vanilla's iteration order, and reused by every block
 * that resolves to the same parts.
 */
public final class ModelRegistry {
	private final int epoch;
	private final ConcurrentHashMap<Key, Integer> ids = new ConcurrentHashMap<>();

	ModelRegistry(int epoch) {
		this.epoch = epoch;
	}

	public int epoch() {
		return epoch;
	}

	public int size() {
		return ids.size();
	}

	/** Native id, {@link Protocol#MODEL_NONE} or {@link Protocol#MODEL_JAVA}. */
	public int idFor(List<BlockStateModelPart> parts) {
		if (parts.isEmpty()) {
			return Protocol.MODEL_NONE;
		}
		for (BlockStateModelPart part : parts) {
			if (!BloomRenderAdapters.isStaticPart(part)) {
				return Protocol.MODEL_JAVA;
			}
		}
		Key key = new Key(parts.toArray());
		Integer id = ids.get(key);
		return id != null ? id : register(key, parts);
	}

	private synchronized int register(Key key, List<BlockStateModelPart> parts) {
		Integer existing = ids.get(key);
		if (existing != null) {
			return existing;
		}
		int quadCount = 0;
		boolean shadeOverride = false;
		for (BlockStateModelPart part : parts) {
			for (Direction d : Direction.values()) {
				quadCount += part.getQuads(d).size();
				shadeOverride |= part.getQuads(d).stream().anyMatch(q -> q.materialInfo().shadeDirectionOverride() != null);
			}
			quadCount += part.getQuads(null).size();
			shadeOverride |= part.getQuads(null).stream().anyMatch(q -> q.materialInfo().shadeDirectionOverride() != null);
		}
		if (shadeOverride && FabricApiAdapter.indigo() != FabricApiAdapter.Indigo.ABSENT) {
			// Indigo's vanilla lighting drops the shade direction override: such models stay with Indigo.
			ids.put(key, Protocol.MODEL_JAVA);
			return Protocol.MODEL_JAVA;
		}
		int size = Protocol.MODEL_HEADER_SIZE + quadCount * Protocol.QUAD_RECORD_SIZE;
		long buf = MemoryUtil.nmemCalloc(1, size);
		int id;
		try {
			MemoryUtil.memPutInt(buf, Protocol.MODEL_MAGIC);
			MemoryUtil.memPutInt(buf + 4, Protocol.VERSION);
			MemoryUtil.memPutInt(buf + 8, epoch);
			MemoryUtil.memPutInt(buf + 12, quadCount);
			MemoryUtil.memPutInt(buf + 16, parts.getFirst().useAmbientOcclusion() ? Protocol.MODEL_FLAG_USE_AO : 0);
			long o = buf + Protocol.MODEL_HEADER_SIZE;
			for (BlockStateModelPart part : parts) {
				for (Direction d : Direction.values()) {
					for (BakedQuad quad : part.getQuads(d)) {
						writeQuad(o, quad, d.ordinal());
						o += Protocol.QUAD_RECORD_SIZE;
					}
				}
				for (BakedQuad quad : part.getQuads(null)) {
					writeQuad(o, quad, Protocol.CULL_NONE);
					o += Protocol.QUAD_RECORD_SIZE;
				}
			}
			id = NativeBridge.registerModel(buf, size);
		} finally {
			MemoryUtil.nmemFree(buf);
		}
		if (id == -Protocol.STATUS_STALE_TABLES) {
			// Tables were replaced meanwhile: this section is compiled by vanilla.
			return Protocol.MODEL_JAVA;
		}
		if (id <= 0) {
			BloomEngine.fail("registering a model (status " + -id + ")", new IllegalStateException(NativeLibrary.lastError()));
			return Protocol.MODEL_JAVA;
		}
		ids.put(key, id);
		return id;
	}

	private static void writeQuad(long o, BakedQuad quad, int cull) {
		for (int v = 0; v < 4; v++) {
			Vector3fc p = quad.position(v);
			MemoryUtil.memPutFloat(o + v * 12L, p.x());
			MemoryUtil.memPutFloat(o + v * 12L + 4, p.y());
			MemoryUtil.memPutFloat(o + v * 12L + 8, p.z());
			long uv = quad.packedUV(v);
			MemoryUtil.memPutFloat(o + 48 + v * 8L, UVPair.unpackU(uv));
			MemoryUtil.memPutFloat(o + 48 + v * 8L + 4, UVPair.unpackV(uv));
		}
		BakedQuad.MaterialInfo info = quad.materialInfo();
		MemoryUtil.memPutInt(o + 80, info.tintIndex());
		MemoryUtil.memPutByte(o + 84, (byte) quad.direction().ordinal());
		MemoryUtil.memPutByte(o + 85, (byte) cull);
		MemoryUtil.memPutByte(o + 86, (byte) info.layer().ordinal());
		Direction shade = info.shadeDirectionOverride();
		MemoryUtil.memPutByte(o + 87, (byte) (shade == null ? Protocol.NO_SHADE_OVERRIDE : shade.ordinal()));
		MemoryUtil.memPutByte(o + 88, (byte) info.lightEmission());
	}

	/** Identity of an ordered list of parts. */
	static final class Key {
		private final Object[] parts;
		private final int hash;

		Key(Object[] parts) {
			this.parts = parts;
			int h = 1;
			for (Object p : parts) {
				h = 31 * h + System.identityHashCode(p);
			}
			this.hash = h;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Key other) || other.parts.length != parts.length) {
				return false;
			}
			for (int i = 0; i < parts.length; i++) {
				if (parts[i] != other.parts[i]) {
					return false;
				}
			}
			return true;
		}

		@Override
		public int hashCode() {
			return hash;
		}

		@Override
		public String toString() {
			return Arrays.toString(parts);
		}
	}
}
