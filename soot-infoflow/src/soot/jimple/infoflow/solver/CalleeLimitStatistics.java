package soot.jimple.infoflow.solver;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import java.util.Collection;

/**
 * Collects statistics on call sites that the data flow solvers skip because
 * they have more callees than the configured limit
 * ({@code maxCalleesPerCallSite}). For such call sites, the solver does not
 * propagate taints into any callee, so flows that pass through these callees
 * are not found. The statistics make this source of incompleteness visible.
 * <p>
 * The statistics are global to the JVM and are reset at the start of every
 * data flow analysis run.
 */
public final class CalleeLimitStatistics {

	private static final Set<Object> skippedCallSites = ConcurrentHashMap.newKeySet();
	private static final LongAdder skippedVisits = new LongAdder();
	private static final AtomicInteger maxCalleesSeen = new AtomicInteger();

	private CalleeLimitStatistics() {
	}

	/**
	 * Records the given call site if it has more callees than the limit allows
	 *
	 * @param callSite The call site
	 * @param callees  The callees of the call site
	 * @param limit    The configured limit. A negative value means no limit.
	 */
	public static void recordIfExceeded(Object callSite, Collection<?> callees, int limit) {
		if (limit < 0 || callees == null || callees.size() <= limit)
			return;
		skippedVisits.increment();
		skippedCallSites.add(callSite);
		int size = callees.size();
		int cur;
		while (size > (cur = maxCalleesSeen.get()) && !maxCalleesSeen.compareAndSet(cur, size)) {
			// retry
		}
	}

	/**
	 * Resets all statistics
	 */
	public static void reset() {
		skippedCallSites.clear();
		skippedVisits.reset();
		maxCalleesSeen.set(0);
	}

	/**
	 * Gets the number of distinct call sites that were skipped
	 */
	public static int getSkippedCallSites() {
		return skippedCallSites.size();
	}

	/**
	 * Gets how often the solvers skipped a call site, counting every edge that
	 * reached a skipped call site
	 */
	public static long getSkippedVisits() {
		return skippedVisits.sum();
	}

	/**
	 * Gets the largest number of callees at any skipped call site
	 */
	public static int getMaxCalleesSeen() {
		return maxCalleesSeen.get();
	}

}
