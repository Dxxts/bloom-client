package com.bloomclient.engine.access;

/** Implemented on {@code BufferBuilder} by {@code BufferBuilderMixin}. */
public interface BloomBufferBuilder {
	/** Appends {@code count} complete vertices in the builder's format, copied from {@code src}. */
	void bloom$appendVertices(long src, int count);
}
