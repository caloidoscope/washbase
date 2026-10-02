package com.washbase.api;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A {@link Clock} that tests set and move by hand (see {@link TestClockConfiguration}). Thread-safe. */
public final class MutableClock extends Clock {

	private volatile Instant now;

	public MutableClock(Instant now) {
		this.now = now;
	}

	public void set(Instant instant) {
		this.now = instant;
	}

	@Override
	public Instant instant() {
		return now;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return Clock.fixed(now, zone);
	}

}
