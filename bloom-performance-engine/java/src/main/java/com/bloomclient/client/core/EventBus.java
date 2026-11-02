package com.bloomclient.client.core;

import com.bloomclient.client.api.event.Subscription;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Bloom's event system. Listeners belong to a mod: they are dispatched only
 * while that mod is enabled, each call isolated by the mod's error guard.
 * Posting never allocates: listeners are kept in copy-on-write arrays per
 * event class. Client thread only.
 */
public final class EventBus {
	static final class Listener implements Subscription {
		final ModContainer owner;
		final Consumer<Object> fn;
		final String where;
		final Class<?> type;
		volatile boolean active = true;

		Listener(ModContainer owner, Class<?> type, Consumer<Object> fn) {
			this.owner = owner;
			this.type = type;
			this.fn = fn;
			this.where = "event " + type.getSimpleName();
		}

		@Override
		public void cancel() {
			if (active) {
				active = false;
				owner.bus().remove(this);
			}
		}

		@Override
		public boolean isActive() {
			return active;
		}
	}

	private static final Listener[] NONE = new Listener[0];
	private final Map<Class<?>, Listener[]> listeners = new HashMap<>();

	@SuppressWarnings("unchecked")
	<E> Listener add(ModContainer owner, Class<E> type, Consumer<? super E> fn) {
		Listener l = new Listener(owner, type, (Consumer<Object>) fn);
		Listener[] current = listeners.getOrDefault(type, NONE);
		Listener[] next = Arrays.copyOf(current, current.length + 1);
		next[current.length] = l;
		listeners.put(type, next);
		return l;
	}

	void remove(Listener l) {
		Listener[] current = listeners.get(l.type);
		if (current == null) return;
		int n = 0;
		Listener[] next = new Listener[current.length];
		for (Listener c : current) if (c != l) next[n++] = c;
		listeners.put(l.type, Arrays.copyOf(next, n));
	}

	/** Dispatches {@code event} to the enabled owners' listeners of its exact class. */
	public void post(Object event) {
		Listener[] ls = listeners.get(event.getClass());
		if (ls == null) return;
		for (Listener l : ls) {
			if (l.active && l.owner.isEnabled()) {
				try {
					l.fn.accept(event);
				} catch (Throwable t) {
					l.owner.reportError(l.where, t);
				}
			}
		}
	}
}
