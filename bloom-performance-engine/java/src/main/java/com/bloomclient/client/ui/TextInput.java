package com.bloomclient.client.ui;

import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * State of one text field: text, cursor and selection. Key codes are SDL
 * scancodes (Minecraft 26.3 uses SDL3 for input).
 */
public final class TextInput {
	private static final int KEY_BACKSPACE = 42, KEY_DELETE = 76, KEY_LEFT = 80, KEY_RIGHT = 79, KEY_HOME = 74, KEY_END = 77;
	private static final int KEY_RETURN = 40, KEY_NUMPADENTER = 88;

	private final StringBuilder text = new StringBuilder();
	private int cursor;
	private int anchor;
	private int maxLength = 256;
	private Predicate<String> filter = s -> true;
	private boolean changed;
	private boolean submitted;
	/** Index of the first visible character. */
	int scroll;
	long lastEdit;

	public TextInput() {
	}

	public TextInput(String initial) {
		set(initial);
	}

	public TextInput maxLength(int max) {
		this.maxLength = max;
		return this;
	}

	public TextInput filter(Predicate<String> filter) {
		this.filter = filter;
		return this;
	}

	public String value() {
		return text.toString();
	}

	public boolean isEmpty() {
		return text.isEmpty();
	}

	public void set(String value) {
		if (value == null) value = "";
		if (value.length() > maxLength) value = value.substring(0, maxLength);
		if (!value.contentEquals(text)) {
			text.setLength(0);
			text.append(value);
			changed = true;
		}
		cursor = anchor = text.length();
		scroll = 0;
	}

	/** True once after the text changed. */
	public boolean consumeChanged() {
		boolean c = changed;
		changed = false;
		return c;
	}

	/** True once after Enter was pressed. */
	public boolean consumeSubmitted() {
		boolean s = submitted;
		submitted = false;
		return s;
	}

	int cursor() {
		return cursor;
	}

	int selectionStart() {
		return Math.min(cursor, anchor);
	}

	int selectionEnd() {
		return Math.max(cursor, anchor);
	}

	void moveCursorTo(int index, boolean select) {
		cursor = Math.max(0, Math.min(text.length(), index));
		if (!select) anchor = cursor;
	}

	void selectAll() {
		anchor = 0;
		cursor = text.length();
	}

	private void insert(String s) {
		int start = selectionStart(), end = selectionEnd();
		String candidate = new StringBuilder(text).replace(start, end, s).toString();
		if (candidate.length() > maxLength) {
			int room = maxLength - (text.length() - (end - start));
			if (room <= 0) return;
			s = s.substring(0, Math.min(s.length(), room));
			candidate = new StringBuilder(text).replace(start, end, s).toString();
		}
		if (!filter.test(candidate)) return;
		text.setLength(0);
		text.append(candidate);
		cursor = anchor = start + s.length();
		changed = true;
		lastEdit = System.currentTimeMillis();
	}

	private void deleteSelection() {
		insert("");
	}

	private int wordLeft(int from) {
		int i = from;
		while (i > 0 && text.charAt(i - 1) == ' ') i--;
		while (i > 0 && text.charAt(i - 1) != ' ') i--;
		return i;
	}

	private int wordRight(int from) {
		int i = from, n = text.length();
		while (i < n && text.charAt(i) != ' ') i++;
		while (i < n && text.charAt(i) == ' ') i++;
		return i;
	}

	public boolean keyPressed(KeyEvent e) {
		boolean shift = e.hasShiftDown();
		boolean word = e.hasControlDownWithQuirk();
		Minecraft mc = Minecraft.getInstance();
		if (e.isSelectAll()) {
			selectAll();
			return true;
		}
		if (e.isCopy()) {
			if (selectionEnd() > selectionStart()) mc.keyboardHandler.setClipboard(text.substring(selectionStart(), selectionEnd()));
			return true;
		}
		if (e.isCut()) {
			if (selectionEnd() > selectionStart()) {
				mc.keyboardHandler.setClipboard(text.substring(selectionStart(), selectionEnd()));
				deleteSelection();
			}
			return true;
		}
		if (e.isPaste()) {
			String clip = mc.keyboardHandler.getClipboard();
			if (clip != null) insert(clip.replace("\r", "").replace("\n", " ").replace("\t", " "));
			return true;
		}
		switch (e.key()) {
			case KEY_BACKSPACE -> {
				if (selectionEnd() == selectionStart()) {
					if (cursor == 0) return true;
					anchor = word ? wordLeft(cursor) : cursor - 1;
				}
				deleteSelection();
				return true;
			}
			case KEY_DELETE -> {
				if (selectionEnd() == selectionStart()) {
					if (cursor >= text.length()) return true;
					anchor = word ? wordRight(cursor) : cursor + 1;
				}
				deleteSelection();
				return true;
			}
			case KEY_LEFT -> {
				if (!shift && selectionEnd() > selectionStart()) moveCursorTo(selectionStart(), false);
				else moveCursorTo(word ? wordLeft(cursor) : cursor - 1, shift);
				return true;
			}
			case KEY_RIGHT -> {
				if (!shift && selectionEnd() > selectionStart()) moveCursorTo(selectionEnd(), false);
				else moveCursorTo(word ? wordRight(cursor) : cursor + 1, shift);
				return true;
			}
			case KEY_HOME -> {
				moveCursorTo(0, shift);
				return true;
			}
			case KEY_END -> {
				moveCursorTo(text.length(), shift);
				return true;
			}
			case KEY_RETURN, KEY_NUMPADENTER -> {
				submitted = true;
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	public boolean charTyped(CharacterEvent e) {
		if (!e.isAllowedChatCharacter()) return false;
		insert(e.codepointAsString());
		return true;
	}
}
