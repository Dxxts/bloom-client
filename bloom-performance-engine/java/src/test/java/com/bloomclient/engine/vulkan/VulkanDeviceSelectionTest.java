package com.bloomclient.engine.vulkan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VulkanDeviceSelectionTest {
	private static final VulkanDeviceSelection.Candidate NVIDIA = new VulkanDeviceSelection.Candidate(1, "NVIDIA GeForce GTX 1660 SUPER", true, null);
	private static final VulkanDeviceSelection.Candidate INTEL = new VulkanDeviceSelection.Candidate(0, "Intel(R) UHD Graphics 630", false, null);

	@Test
	void numbersSelectByIndex() {
		assertTrue(VulkanDeviceSelection.matches(NVIDIA, "1"));
		assertFalse(VulkanDeviceSelection.matches(INTEL, "1"));
	}

	@Test
	void textSelectsByCaseInsensitiveNameFragment() {
		assertTrue(VulkanDeviceSelection.matches(NVIDIA, "geforce"));
		assertTrue(VulkanDeviceSelection.matches(INTEL, "UHD"));
		assertFalse(VulkanDeviceSelection.matches(INTEL, "radeon"));
	}
}
