# Memory and completeness changes (branch `memory-fixes`)

Base: `develop` at commit `7eb45c5`. Purpose: make it possible to configure, observe and detect the
memory-related truncation of FlowDroid results, for analyses of large Android apps.

These changes do not alter the taint analysis itself. No flow function, aliasing or propagation logic
was modified. Default behaviour is unchanged unless a new option is given.

## 1. New command-line options (`soot-infoflow-cmd`, `MainClass`)

| Option | Long form | Effect |
|---|---|---|
| `-ds SPARSECONTEXT` | `--dataflowsolver` | Selects `SparseContextFlowSensitive`, which was not reachable from the command line before. The help text now lists `CONTEXTFLOWSENSITIVE, SPARSECONTEXT, FLOWINSENSITIVE, GC, FPC`. |
| `-sps DENSE\|SIMPLE\|PRECISE` | `--sparsestrategy` | Propagation strategy of the sparse solver (default: `PRECISE`). |
| `-mth <0-1>` | `--memorythreshold` | Fraction of the tenured-generation heap at which the solvers are terminated (default 0.9). Values outside (0, 1] are rejected. |
| `-ir` | `--incrementalresults` | Reports results as they are found. |
| `-mcs <n>` | `--maxcallees` | Maximum callees per call site (default 75, negative = unlimited). |
| `-mjp <n>` | `--maxjoinpointabstractions` | Maximum abstractions per join point (default 10, negative = unlimited). |
| `-map <n>` | `--maxabstractionpathlength` | Maximum length over which an abstraction is propagated (default 100, negative = unlimited). |
| `-fi` | `--failonincomplete` | See section 2. |

All of these call setters that already existed in `InfoflowConfiguration`.

## 2. Truncated results are now reported (`-fi`)

Previously, a run that was terminated by the memory watcher (or by a timeout) produced a partial result
file without any marker.

- Without `-fi`: a line `INCOMPLETE RESULTS for <apk>: the analysis was terminated (<reason>), reported
  flows are only a subset` is printed to stderr and logged. Nothing else changes.
- With `-fi`: additionally, a marker file `<output file>.INCOMPLETE` is written (it contains the message and
  the termination state), and the process exits with code `3` (`MainClass.EXIT_CODE_INCOMPLETE_RESULTS`).

Reasons are taken from `InfoflowResults.wasTerminatedOutOfMemory()` and `wasAbortedTimeout()`.

Note: when a wrapper subclass of `MainClass` overrides `run`, it must evaluate `incompleteResults` itself;
the exit code is set in `MainClass.main`.

## 3. Skipped call sites are counted (`CalleeLimitStatistics`)

All five solvers skip a call site that has more callees than `maxCalleesPerCallSite`: no taint is propagated
into any callee of that call site. With a CHA call graph this can be frequent and was previously silent.

- New class `solver/CalleeLimitStatistics` (global, reset at the start of each solve).
- Hooked into `fastSolver/IFDSSolver`, `fastSolver/flowInsensitive/FlowInsensitiveSolver`,
  `gcSolver/IFDSSolver`, `gcSolver/fpc/IFDSSolver` and `sparseSolver/SparseInfoflowSolver`
  (one line each).
- `AbstractInfoflow` logs a warning after solving, stating the number of distinct call sites, the number of
  visits, the limit and the largest callee count. Nothing is logged when no call site was skipped.

## 4. Sparse solver statistics

- `SparseInfoflowSolver` counts the normal-flow successors it scheduled and the number a dense solver would
  have scheduled (`getSparsificationStatistics()`).
- `AbstractSparsePropagation.getSparseCfgSize()` exposes the number of entries in the sparse CFG cache,
  which is never evicted.
- `AbstractInfoflow` logs both values after solving when the sparse solver is used.

## Files changed

- `soot-infoflow-cmd/src/soot/jimple/infoflow/cmd/MainClass.java`
- `soot-infoflow/src/soot/jimple/infoflow/AbstractInfoflow.java`
- `soot-infoflow/src/soot/jimple/infoflow/solver/CalleeLimitStatistics.java` (new)
- `soot-infoflow/src/soot/jimple/infoflow/solver/fastSolver/IFDSSolver.java`
- `soot-infoflow/src/soot/jimple/infoflow/solver/fastSolver/flowInsensitive/FlowInsensitiveSolver.java`
- `soot-infoflow/src/soot/jimple/infoflow/solver/gcSolver/IFDSSolver.java`
- `soot-infoflow/src/soot/jimple/infoflow/solver/gcSolver/fpc/IFDSSolver.java`
- `soot-infoflow/src/soot/jimple/infoflow/solver/sparseSolver/SparseInfoflowSolver.java`
- `soot-infoflow/src/soot/jimple/infoflow/solver/sparseSolver/propagation/AbstractSparsePropagation.java`

About 290 lines were added in total (80 of them in the new class; the rest mostly option parsing and comments).

## Building

`develop` depends on `soot:4.8.0-SNAPSHOT`, which is no longer served by `oss.sonatype.org`. Build Soot's
`develop` branch locally and install it first:

```
git clone https://github.com/soot-oss/soot.git && cd soot
mvn -DskipTests -Dmaven.javadoc.skip=true install
cd ../flowdroid && mvn -DskipTests -pl soot-infoflow-cmd -am compile
```

Compiled classes are written to `<module>/build/classes`.

## Verification

Done:
- Compiles against Soot 4.8.0-SNAPSHOT (Soot `develop` at `c7c62ce`) on OpenJDK 21.
- Forward `BasicTests` give identical results under all seven solver configurations
  (`ContextFlowSensitive`, `SparseContextFlowSensitive` with `Precise`/`Simple`/`Dense`,
  `GarbageCollecting`, `FineGrainedGC`, `FlowInsensitive`): 7 of 9 tests pass. The same two tests
  (`simpleTest`, `overwriteInCalleeTest2`) also fail on the unmodified commit in this environment.
- The incomplete-result check was run directly for success, out-of-memory and timeout, with and without `-fi`.

Not done:
- No end-to-end Android run (no platform jar and no APKs were available in the build environment).
- The `System.exit(3)` path in `main` was not executed.
- No large-app memory measurements; whether any of this lets Coursicle or Zotero complete is untested.

## Suggested use

```
java -Xmx200g -cp <classpath> soot.jimple.infoflow.cmd.MainClass \
  -a app.apk -p <android-platforms> -s sources_sinks.txt -o flows.xml \
  -ds FPC -st 1 -pr NONE -fi
```

Compare `-ds GC`, `-ds FPC`, `-ds SPARSECONTEXT -sps PRECISE` and the default solver on the same component,
and check the log for the skipped-call-site warning. If many call sites are skipped, raise `-mcs` or use a
more precise call graph (`-cg SPARK` or `RTA`).

## Not included

- A per-source batch driver that flushes results after each batch.
- Combining the sparse solver with the garbage-collecting solvers, and bounding the sparse cache.
