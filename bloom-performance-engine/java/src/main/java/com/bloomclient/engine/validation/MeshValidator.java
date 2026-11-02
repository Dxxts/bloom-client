package com.bloomclient.engine.validation;

import com.bloomclient.engine.BloomEngine;
import com.bloomclient.engine.bridge.Protocol;
import com.bloomclient.engine.mesh.SectionMesher;
import com.bloomclient.engine.profiling.EngineProfiler;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;

/**
 * Visual regression check at runtime: recompiles a sample of natively
 * meshed sections with the vanilla renderer and compares the meshes quad by
 * quad (as multisets of 112-byte quads, so draw order inside a layer does
 * not matter), plus the visibility sets.
 *
 * <p>A difference is ignored only if the light the native mesh read has
 * changed since (light is read live, see {@code SectionMesher.lightChanged});
 * any other difference disables the engine and is written to
 * {@code .bloom/validation-failure.json}: Bloom never keeps rendering a mesh
 * that differs from vanilla.
 */
public final class MeshValidator {
	private static final AtomicLong COUNTER = new AtomicLong();
	private static final AtomicLong PASSED = new AtomicLong();
	private static final AtomicLong SKIPPED = new AtomicLong();
	private static final ThreadLocal<SectionBufferBuilderPack> PACKS = ThreadLocal.withInitial(SectionBufferBuilderPack::new);
	private static final int QUAD_BYTES = Protocol.VERTEX_SIZE * 4;

	private MeshValidator() {
	}

	public static long passed() {
		return PASSED.get();
	}

	/** Samples discarded because the world's light changed between the two compiles. */
	public static long skipped() {
		return SKIPPED.get();
	}

	public static void maybeValidate(SectionCompiler compiler, SectionPos pos, RenderSectionRegion region, VertexSorting sorting, SectionCompiler.Results nativeResults) {
		int every = BloomEngine.config().validateEvery;
		if (every <= 0 || COUNTER.incrementAndGet() % every != 0) {
			return;
		}
		SectionMesher mesher = SectionMesher.current();
		SectionBufferBuilderPack pack = PACKS.get();
		mesher.setBypass(true);
		SectionCompiler.Results vanilla = null;
		try {
			vanilla = compiler.compile(pos, region, sorting, pack);
			String difference = compare(nativeResults, vanilla);
			if (difference == null) {
				PASSED.incrementAndGet();
			} else if (mesher.lightChanged(pos, region)) {
				// Not a mesher error: the light engine updated the section in between.
				SKIPPED.incrementAndGet();
			} else {
				Map<String, Object> report = new LinkedHashMap<>();
				report.put("section", pos.toString());
				report.put("difference", difference);
				EngineProfiler.write(BloomEngine.dataDir().resolve("validation-failure.json"), report);
				BloomEngine.fail("validating section " + pos + ": " + difference, null);
			}
		} catch (Throwable t) {
			BloomEngine.fail("validating section " + pos, t);
		} finally {
			mesher.setBypass(false);
			if (vanilla != null) {
				vanilla.release();
			}
			pack.clearAll();
		}
	}

	@SuppressWarnings("unchecked")
	static String compare(SectionCompiler.Results nativeResults, SectionCompiler.Results vanilla) {
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			List<byte[]> a = quads(((Map<ChunkSectionLayer, MeshData>) nativeResults.renderedLayers).get(layer));
			List<byte[]> b = quads(((Map<ChunkSectionLayer, MeshData>) vanilla.renderedLayers).get(layer));
			if (a.size() != b.size()) {
				return layer.label() + ": " + a.size() + " quads natively, " + b.size() + " in vanilla";
			}
			for (int i = 0; i < a.size(); i++) {
				if (!Arrays.equals(a.get(i), b.get(i))) {
					return layer.label() + ": quad differs, native " + describe(a.get(i)) + " vanilla " + describe(b.get(i));
				}
			}
		}
		for (Direction from : Direction.values()) {
			for (Direction to : Direction.values()) {
				if (nativeResults.visibilitySet.visibilityBetween(from, to) != vanilla.visibilitySet.visibilityBetween(from, to)) {
					return "visibility differs between " + from + " and " + to;
				}
			}
		}
		return null;
	}

	private static List<byte[]> quads(MeshData mesh) {
		List<byte[]> out = new ArrayList<>();
		if (mesh == null) {
			return out;
		}
		ByteBuffer buf = mesh.vertexBuffer();
		int vertices = mesh.drawState().vertexCount();
		for (int q = 0; q < vertices / 4; q++) {
			byte[] quad = new byte[QUAD_BYTES];
			buf.get(buf.position() + q * QUAD_BYTES, quad);
			out.add(quad);
		}
		out.sort(Arrays::compare);
		return out;
	}

	private static String describe(byte[] quad) {
		ByteBuffer b = ByteBuffer.wrap(quad).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		StringBuilder s = new StringBuilder("[");
		for (int v = 0; v < 4; v++) {
			int o = v * Protocol.VERTEX_SIZE;
			// Float.toString is the shortest exact representation: differences of one ulp stay visible.
			s.append('(').append(b.getFloat(o)).append(' ').append(b.getFloat(o + 4)).append(' ').append(b.getFloat(o + 8))
					.append(String.format(" rgba=%08x uv=", b.getInt(o + 12))).append(b.getFloat(o + 16)).append(',').append(b.getFloat(o + 20))
					.append(String.format(" light=%08x)", b.getInt(o + 24)));
		}
		return s.append(']').toString();
	}
}
