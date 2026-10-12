package com.jq.handler;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class HandlerRegistry {

    private final Map<String, JobHandler> handlers = new HashMap<>();

    public HandlerRegistry(List<JobHandler> handlerList) {
        for (JobHandler handler : handlerList) {
            handlers.put(handler.getType(), handler);
        }
    }

    // null if unknown
    public JobHandler find(String jobType) {
        return handlers.get(jobType);
    }
}
