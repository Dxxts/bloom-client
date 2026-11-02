package com.bloomclient.client.api.event;

/** Handle returned by listener and task registrations. */
public interface Subscription {
	/** Stops the listener or task. Safe to call more than once. */
	void cancel();

	boolean isActive();
}
