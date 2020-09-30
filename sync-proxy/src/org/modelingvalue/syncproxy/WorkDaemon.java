package org.modelingvalue.syncproxy;

import java.io.*;

@SuppressWarnings("unused")
public abstract class WorkDaemon<WORK> extends Thread implements Closeable {
    private boolean   stop;
    private boolean   busy = true;
    private Throwable throwable;

    public WorkDaemon(String name) {
        super(name);
        setDaemon(true);
    }

    protected abstract WORK waitForWork() throws InterruptedException;

    protected abstract void execute(WORK w) throws InterruptedException;

    public void run() {
        while (!stop) {
            try {
                busy = false;
                WORK w = waitForWork();
                busy = true;
                execute(w);
            } catch (InterruptedException e) {
                if (!stop) {
                    throwable = new Error("unexpected interrupt", e);
                }
            } catch (Error e) {
                if (!(e.getCause() instanceof InterruptedException)) {
                    throwable = new Error("unexpected interrupt", e);
                }
            } catch (Throwable t) {
                throwable = new Error("unexpected throwable", t);
            }
        }
    }

    @Override
    public void close() {
        stop = true;
    }

    public void closeAndInterrupt() {
        close();
        interrupt();
    }

    public boolean isBusy() {
        return busy && isAlive();
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public void join_() {
        try {
            join();
        } catch (InterruptedException e) {
            throw new Error(e);
        }
    }
}
