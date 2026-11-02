package com.bloomclient.engine;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rendering errors Bloom observed in this session (engine failures, rejected
 * validation, unavailable GPU features), for the F3 screen and the reports.
 * Thread-safe; keeps the last few messages.
 */
public final class RenderingErrors {
	private static final int KEEP = 8;
	private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
	private static final AtomicInteger COUNT = new AtomicInteger();
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	private RenderingErrors() {
	}

	public static void record(String message) {
		COUNT.incrementAndGet();
		synchronized (RECENT) {
			if (RECENT.size() == KEEP) RECENT.removeFirst();
			RECENT.addLast(LocalTime.now().format(TIME) + " " + message);
		}
	}

	public static int count() {
		return COUNT.get();
	}

	public static List<String> recent() {
		synchronized (RECENT) {
			return new ArrayList<>(RECENT);
		}
	}
}
