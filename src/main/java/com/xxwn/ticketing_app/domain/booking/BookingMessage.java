package com.xxwn.ticketing_app.domain.booking;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

public record BookingMessage (
        Long userId,
        Long seatId,
        Long concertId
) implements Serializable{
    public BookingMessage{}

    public static BookingMessage create(Long userId, BookingMessage request){
        return new BookingMessage(userId,request.seatId(),request.concertId());
    }
}
