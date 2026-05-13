package com.xxwn.ticket_rush.domain.booking;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

public record BookingMessage (
        String userId,
        Long seatId,
        Long eventId
) implements Serializable{
    public BookingMessage{}

    public static BookingMessage create(String userId, BookingMessage request){
        return new BookingMessage(userId,request.seatId(),request.eventId());
    }
}
