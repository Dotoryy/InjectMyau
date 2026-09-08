package myau.lag.timeout;

public abstract class AbstractTimeout {
    private volatile boolean forcefullyTimedOut = false;

    protected abstract boolean shouldHaveTimedOut();

    public final boolean isTimedOut() {
        return this.forcefullyTimedOut || this.shouldHaveTimedOut();
    }

    public final void forceTimeOut() {
        this.forcefullyTimedOut = true;
    }
}
