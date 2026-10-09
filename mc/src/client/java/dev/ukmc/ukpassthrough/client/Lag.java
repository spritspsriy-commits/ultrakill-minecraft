package dev.ukmc.ukpassthrough.client;

import dev.ukmc.ukpassthrough.UKPassthrough;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Slow frames on Minecraft's side, to the log (round 28b, the user's report of lag spikes; uk/src/Lag.cs is ULTRAKILL's side). The
 * mod's own client tick work is timed part by part ({@link #mark}); a frame longer than {@link #SLOW_MS} is logged with the parts of the
 * last tick, at most every few seconds, and every ten seconds a line says how many frames there were and how many were slow. Client
 * thread.
 */
final class Lag {
	private static final double SLOW_MS = 50.0;
	private static final long LOG_EVERY_NANOS = 3_000_000_000L;
	private static final long SUMMARY_EVERY_NANOS = 10_000_000_000L;

	private static final Map<String, Double> PARTS = new LinkedHashMap<>();
	private static long frameAt;
	private static long partAt;
	private static long nextLog;
	private static long summaryAt;
	private static int frames;
	private static int slow;
	private static double worst;

	private Lag() {
	}

	/** Every frame (the HUD's status element). */
	static void frame() {
		long now = System.nanoTime();
		if (frameAt != 0L) {
			double ms = (now - frameAt) / 1.0e6;
			frames++;
			worst = Math.max(worst, ms);
			if (ms > SLOW_MS) {
				slow++;
				if (now >= nextLog) {
					nextLog = now + LOG_EVERY_NANOS;
					StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "lag: a frame took %.0f ms; the mod's parts of the last tick:", ms));
					PARTS.forEach((k, v) -> {
						if (v >= 1.0) {
							sb.append(String.format(Locale.ROOT, " %s %.1f", k, v));
						}
					});
					UKPassthrough.LOG.info(sb.append(" ms").toString());
				}
			}
		}

		frameAt = now;
		if (summaryAt == 0L) {
			summaryAt = now + SUMMARY_EVERY_NANOS;
		} else if (now >= summaryAt) {
			summaryAt = now + SUMMARY_EVERY_NANOS;
			UKPassthrough.LOG.info(String.format(Locale.ROOT, "lag: %d frames in the last 10 s, %d over %.0f ms, the longest %.0f ms", frames, slow, SLOW_MS, worst));
			frames = 0;
			slow = 0;
			worst = 0.0;
		}
	}

	/** The start of the mod's client tick. */
	static void tickStart() {
		PARTS.clear();
		partAt = System.nanoTime();
	}

	/** The time since the last mark goes to {@code part}. */
	static void mark(final String part) {
		long now = System.nanoTime();
		PARTS.merge(part, (now - partAt) / 1.0e6, Double::sum);
		partAt = now;
	}
}
