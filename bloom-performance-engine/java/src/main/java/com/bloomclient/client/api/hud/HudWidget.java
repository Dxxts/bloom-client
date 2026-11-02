package com.bloomclient.client.api.hud;

import com.bloomclient.client.api.GameInfo;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.Tr;
import java.util.List;

/**
 * A widget type for the HUD Studio. Bloom calls {@link #update} for each
 * element using the widget at most every {@link #updateIntervalMs()}
 * milliseconds; rendering only draws the cached lines, so frames never pay
 * for formatting.
 *
 * <pre>{@code
 * public final class DayWidget extends HudWidget {
 *     public DayWidget() { super("my-mod:day", "Day", "Current world day", Icon.CLOCK); }
 *     @Override public void update(HudElement e, HudLines out, GameInfo game) {
 *         long day = Minecraft.getInstance().level.getOverworldClockTime() / 24000L;
 *         out.add("Day ", Long.toString(day));
 *     }
 * }
 * }</pre>
 *
 * Widgets must show only real data: when a value is not available, return
 * {@code false} from {@link #isAvailable} (the element is hidden in game and
 * marked as unavailable in the Studio).
 */
public abstract class HudWidget {
	private final String id;
	private final String name;
	private final String description;
	private final Icon icon;

	/**
	 * @param id   unique id, conventionally {@code <mod-id>:<name>}
	 * @param name translation key or plain text
	 */
	protected HudWidget(String id, String name, String description, Icon icon) {
		this.id = id;
		this.name = name;
		this.description = description;
		this.icon = icon;
	}

	public final String id() {
		return id;
	}

	public String displayName() {
		return Tr.handles(name) ? Tr.t(name) : name;
	}

	public String displayDescription() {
		return Tr.handles(description) ? Tr.t(description) : description;
	}

	public Icon icon() {
		return icon;
	}

	public int updateIntervalMs() {
		return 250;
	}

	public List<WidgetOption> options() {
		return List.of();
	}

	/** Default placement and style for a newly added element. */
	public void applyDefaults(HudElement element) {
	}

	/** Whether the data can be shown now (only called in a world). */
	public boolean isAvailable(GameInfo game) {
		return true;
	}

	/** Text shown in the Studio when {@link #isAvailable} is false. */
	public String unavailableText() {
		return Tr.t("bloom.hud.unavailable");
	}

	/** Fills {@code out}; Bloom calls {@code out.begin()} before and {@code out.end()} after. */
	public abstract void update(HudElement element, HudLines out, GameInfo game);
}
