package com.bloomclient.engine.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bloomclient.engine.compatibility.CompatibilityDatabase.Status;
import com.bloomclient.engine.compatibility.CompatibilityReport.ModEntry;
import com.bloomclient.engine.compatibility.CompatibilityReport.ModeSource;
import com.bloomclient.engine.compatibility.CompatibilityReport.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompatibilityLayerTest {
	private static ModEntry mod(String id, Verdict verdict) {
		ModEntry m = new ModEntry();
		m.id = id;
		m.version = "1.0.0";
		m.verdict = verdict;
		if (verdict == Verdict.UNVERIFIED_TERRAIN_HOOKS) m.renderMixinTargets.add("net.minecraft.client.renderer.block.ModelBlockRenderer");
		return m;
	}

	private static CompatibilityDatabase database(CompatibilityDatabase.Entry... entries) {
		CompatibilityDatabase.Data data = new CompatibilityDatabase.Data();
		data.schema = 1;
		data.sequence = 1;
		data.entries = new ArrayList<>(List.of(entries));
		return CompatibilityDatabase.choose(data, null, new ArrayList<>());
	}

	private static CompatibilityDatabase.Entry entry(String id, Status status) {
		CompatibilityDatabase.Entry e = new CompatibilityDatabase.Entry();
		e.modId = id;
		e.status = status;
		return e;
	}

	@Test
	void analysisTurnsTheEngineOffOnlyForRendererReplacementsAndUnverifiedHooks() {
		CompatibilityReport report = new CompatibilityReport();
		report.mods.add(mod("plain", Verdict.NO_RENDER_HOOKS));
		report.mods.add(mod("entities", Verdict.RENDERS_OUTSIDE_TERRAIN));
		report.mods.add(mod("hooks", Verdict.UNVERIFIED_TERRAIN_HOOKS));
		report.mods.add(mod("sodium", Verdict.REPLACES_CHUNK_RENDERER));
		CompatibilityLayer.resolve(report, CompatibilityDatabase.empty("test"), Map.of(), false, "26.3");
		assertEquals(Status.AUTOMATIC_ADAPTER, report.mods.get(0).mode);
		assertEquals(Status.AUTOMATIC_ADAPTER, report.mods.get(1).mode);
		assertEquals(Status.ENGINE_OFF, report.mods.get(2).mode);
		assertEquals(Status.ENGINE_OFF, report.mods.get(3).mode);
		assertTrue(report.blocking());
		assertTrue(report.blockingReason().contains("hooks"));
	}

	@Test
	void databaseDecidesUnlessTheUserOverrides() {
		CompatibilityReport report = new CompatibilityReport();
		report.mods.add(mod("decor", Verdict.NO_RENDER_HOOKS));
		report.mods.add(mod("machines", Verdict.NO_RENDER_HOOKS));
		CompatibilityDatabase.Entry decor = entry("decor", Status.NATIVE_ADAPTER);
		decor.staticModelClasses.add("com.example.decor.StaticModel");
		CompatibilityDatabase.Entry machines = entry("machines", Status.JAVA_FALLBACK);
		machines.namespaces.add("machines_blocks");
		CompatibilityLayer.Decisions d = CompatibilityLayer.resolve(report, database(decor, machines), Map.of(), false, "26.3");
		assertEquals(Status.NATIVE_ADAPTER, report.mods.get(0).mode);
		assertEquals(ModeSource.DATABASE, report.mods.get(0).modeSource);
		assertEquals(List.of("com.example.decor.StaticModel"), List.copyOf(d.adapterClasses()));
		assertEquals(List.of("machines_blocks"), List.copyOf(d.fallbackNamespaces()));

		// Reversible without code: the user sends "decor" to the Java renderer.
		CompatibilityReport again = new CompatibilityReport();
		again.mods.add(mod("decor", Verdict.NO_RENDER_HOOKS));
		CompatibilityLayer.Decisions d2 = CompatibilityLayer.resolve(again, database(decor), Map.of("decor", "java_fallback"), false, "26.3");
		assertEquals(Status.JAVA_FALLBACK, again.mods.getFirst().mode);
		assertEquals(ModeSource.USER_OVERRIDE, again.mods.getFirst().modeSource);
		assertTrue(d2.adapterClasses().isEmpty());
		assertEquals(List.of("decor"), List.copyOf(d2.fallbackNamespaces()));
	}

	@Test
	void databaseCannotVouchForTerrainHooksItDidNotValidate() {
		CompatibilityReport report = new CompatibilityReport();
		report.mods.add(mod("hooks", Verdict.UNVERIFIED_TERRAIN_HOOKS));
		CompatibilityLayer.resolve(report, database(entry("hooks", Status.AUTOMATIC_ADAPTER)), Map.of(), false, "26.3");
		assertEquals(Status.ENGINE_OFF, report.mods.getFirst().mode);

		CompatibilityReport verified = new CompatibilityReport();
		verified.mods.add(mod("hooks", Verdict.UNVERIFIED_TERRAIN_HOOKS));
		CompatibilityDatabase.Entry e = entry("hooks", Status.AUTOMATIC_ADAPTER);
		e.terrainHooksVerified = true;
		CompatibilityLayer.resolve(verified, database(e), Map.of(), false, "26.3");
		assertEquals(Status.AUTOMATIC_ADAPTER, verified.mods.getFirst().mode);
		assertEquals(ModeSource.DATABASE, verified.mods.getFirst().modeSource);
	}

	@Test
	void unknownOverridesAreIgnoredAndReported() {
		CompatibilityReport report = new CompatibilityReport();
		report.mods.add(mod("sodium", Verdict.REPLACES_CHUNK_RENDERER));
		CompatibilityLayer.resolve(report, CompatibilityDatabase.empty("test"), Map.of("sodium", "faster please"), false, "26.3");
		assertEquals(Status.ENGINE_OFF, report.mods.getFirst().mode);
		assertTrue(report.mods.getFirst().reasons.getFirst().contains("ignored unknown override"));
	}

	@Test
	void tableStatesRefineTheAutomaticMode() {
		CompatibilityReport report = new CompatibilityReport();
		report.mods.add(mod("onlycustom", Verdict.NO_RENDER_HOOKS));
		report.mods.add(mod("registered", Verdict.NO_RENDER_HOOKS));
		CompatibilityLayer.resolve(report, CompatibilityDatabase.empty("test"), Map.of(), false, "26.3");
		CompatibilityReport.States custom = new CompatibilityReport.States();
		custom.fallback.put("UNVERIFIED_MODEL_CLASS", 12);
		CompatibilityReport.States registered = new CompatibilityReport.States();
		registered.adapter = 4;
		CompatibilityLayer.applyStates(report, Map.of("onlycustom", custom, "registered", registered));
		assertEquals(Status.JAVA_FALLBACK, report.mods.get(0).mode);
		assertEquals(Status.NATIVE_ADAPTER, report.mods.get(1).mode);
	}
}
