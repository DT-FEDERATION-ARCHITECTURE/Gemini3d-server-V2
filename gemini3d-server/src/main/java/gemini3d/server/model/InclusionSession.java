package gemini3d.server.model;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class InclusionSession {

    public enum Status { CREATED, RUNNING, FINISHED, STOPPED, ERROR }

    private final String id;
    private final String automatonName;
    private final String traceFile;
    private final Instant createdAt;

    private volatile Status status;
    private volatile String errorMessage;
    private final AtomicInteger totalSteps = new AtomicInteger(0);
    private final AtomicInteger okCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);

    private volatile Thread emulatorThread;
    private volatile Thread sequencerThread;

    public InclusionSession(String id, String automatonName, String traceFile) {
        this.id = id;
        this.automatonName = automatonName;
        this.traceFile = traceFile;
        this.createdAt = Instant.now();
        this.status = Status.CREATED;
    }

    public void markRunning(Thread t1, Thread t2) {
        this.emulatorThread = t1; this.sequencerThread = t2; this.status = Status.RUNNING;
    }
    public void markFinished() { this.status = Status.FINISHED; }
    public void markError(String msg) { this.errorMessage = msg; this.status = Status.ERROR; }
    public void stop() {
        this.status = Status.STOPPED;
        if (emulatorThread != null) emulatorThread.interrupt();
        if (sequencerThread != null) sequencerThread.interrupt();
    }

    public void recordOk()   { totalSteps.incrementAndGet(); okCount.incrementAndGet(); }
    public void recordFail() { totalSteps.incrementAndGet(); failCount.incrementAndGet(); }

    public String getId()             { return id; }
    public String getAutomatonName()  { return automatonName; }
    public String getTraceFile()      { return traceFile; }
    public Status getStatus()         { return status; }
    public String getErrorMessage()   { return errorMessage; }
    public int getTotalSteps()        { return totalSteps.get(); }
    public int getOkCount()           { return okCount.get(); }
    public int getFailCount()         { return failCount.get(); }

    public Map<String, Object> toStatusMap() {
        return Map.of(
                "sessionId", id, "automaton", automatonName, "traceFile", traceFile,
                "status", status.name(), "totalSteps", totalSteps.get(),
                "ok", okCount.get(), "fail", failCount.get(),
                "verdict", failCount.get() == 0 ? "CONFORMS" : "VIOLATIONS_DETECTED");
    }
}