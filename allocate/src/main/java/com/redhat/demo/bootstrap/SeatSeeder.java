package com.redhat.demo.bootstrap;

import java.util.List;

import com.redhat.demo.entity.Seat;
import com.redhat.demo.repository.SeatRepository;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class SeatSeeder {

    @Inject
    SeatRepository seatRepository;

    @Transactional
    void onStart(@Observes StartupEvent event) {
        if (seatRepository.count() > 0) {
            return;
        }

        List<Seat> seats = List.of(
                new Seat(null, "A1", 55.00, "FREE", null),
                new Seat(null, "A2", 55.00, "FREE", null),
                new Seat(null, "A3", 55.00, "FREE", null),
                new Seat(null, "A4", 60.00, "FREE", null),
                new Seat(null, "A5", 60.00, "FREE", null),
                new Seat(null, "A6", 65.00, "FREE", null),
                new Seat(null, "B1", 65.00, "FREE", null),
                new Seat(null, "B2", 65.00, "FREE", null),
                new Seat(null, "B3", 65.00, "FREE", null),
                new Seat(null, "B4", 70.00, "FREE", null),
                new Seat(null, "B5", 70.00, "FREE", null),
                new Seat(null, "B6", 75.00, "FREE", null),
                new Seat(null, "C1", 75.00, "FREE", null),
                new Seat(null, "C2", 75.00, "FREE", null),
                new Seat(null, "C3", 80.00, "FREE", null),
                new Seat(null, "C4", 80.00, "FREE", null),
                new Seat(null, "C5", 85.00, "FREE", null),
                new Seat(null, "C6", 90.00, "FREE", null));

        seatRepository.persist(seats);
    }
}

