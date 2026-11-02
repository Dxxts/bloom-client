package com.bloomclient.engine.access;

/** Duck interface added to {@code FrontendGpuDevice}: its backend (OpenGL or Vulkan). */
public interface BloomGpuDevice {
	Object bloom$backend();
}
