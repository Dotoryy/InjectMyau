package myau.lag.queue.node.impl;

import myau.lag.api.LagRequest;
import myau.lag.queue.node.api.AbstractLagNode;

public final class AddRequestLagNode extends AbstractLagNode {
    private final LagRequest request;

    public AddRequestLagNode(LagRequest request) {
        this.request = request;
    }

    public LagRequest getRequest() {
        return this.request;
    }
}
