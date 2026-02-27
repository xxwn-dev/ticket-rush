package com.xxwn.ticketing_app.domain.seat;

import com.xxwn.ticketing_app.domain.concert.Concert;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concert_id")
    private Concert concert;

    private String seatNumber;

    @Enumerated(EnumType.STRING)
    private SeatStatus status;

    public void reserve(){
        if(this.status == SeatStatus.RESERVED){
            throw new IllegalStateException("Already reserved");
        }
        this.status = SeatStatus.RESERVED;
    }

    public void cancel() {
        this.status = SeatStatus.AVAILABLE;
    }
}
