package com.bloomclient.engine.mixin;

import com.bloomclient.engine.access.BloomBufferBuilder;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(BufferBuilder.class)
abstract class BufferBuilderMixin implements BloomBufferBuilder {
	@Shadow @Final private ByteBufferBuilder buffer;
	@Shadow private long vertexPointer;
	@Shadow private int vertices;
	@Shadow @Final private int vertexSize;
	@Shadow private int elementsToFill;

	@Shadow
	private void ensureBuilding() {
		throw new AssertionError();
	}

	@Shadow
	private void endLastVertex() {
		throw new AssertionError();
	}

	@Override
	public void bloom$appendVertices(long src, int count) {
		if (count <= 0) {
			return;
		}
		if (vertexSize != com.bloomclient.engine.bridge.Protocol.VERTEX_SIZE) {
			throw new IllegalStateException("Bloom vertices need the BLOCK format, builder uses " + vertexSize + " bytes");
		}
		ensureBuilding();
		endLastVertex();
		if ((long) vertices + count > 16_777_215L) {
			throw new IllegalStateException("Trying to write too many vertices (>16777215) into BufferBuilder");
		}
		int bytes = count * vertexSize;
		long dst = buffer.reserve(bytes);
		MemoryUtil.memCopy(src, dst, bytes);
		vertices += count;
		vertexPointer = dst + bytes - vertexSize;
		elementsToFill = 0;
	}
}
