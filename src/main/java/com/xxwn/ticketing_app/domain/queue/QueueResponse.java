package com.xxwn.ticketing_app.domain.queue;

public record QueueResponse(
        Long rank,
        Long behindCount,
        String status
) {
    public static QueueResponse active(){
        return new QueueResponse(0L, 0L, "ACTIVE");
    }
}
