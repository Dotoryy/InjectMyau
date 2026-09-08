package myau.lag.api;

import myau.lag.timeout.AbstractTimeout;

import java.util.Set;

public final class LagRequest {
    private final Set<EnumLagDirection> directions;
    private final AbstractTimeout timeout;

    public LagRequest(Set<EnumLagDirection> directions, AbstractTimeout timeout) {
        this.directions = directions;
        this.timeout = timeout;
    }

    public Set<EnumLagDirection> getDirections() {
        return this.directions;
    }

    public AbstractTimeout getTimeout() {
        return this.timeout;
    }
}
