package com.jq;

import com.jq.handler.HandlerRegistry;
import com.jq.handler.JobHandler;

import java.util.List;

// test handler
public class FakeHandler implements JobHandler {

    public interface Action {
        void run(String payload, String idempotencyKey, int attempt) throws Exception;
    }

    private final String type;
    private final Action action;

    public FakeHandler(String type, Action action) {
        this.type = type;
        this.action = action;
    }

    public static HandlerRegistry registryOf(JobHandler... handlers) {
        return new HandlerRegistry(List.of(handlers));
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public void handle(String payload, String idempotencyKey, int attempt) throws Exception {
        action.run(payload, idempotencyKey, attempt);
    }
}
