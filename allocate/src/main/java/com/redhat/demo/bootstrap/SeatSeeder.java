package com.redhat.demo.bootstrap;

import java.util.ArrayList;
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

    private static final int TARGET_ROWS = 20; // A..T
    private static final int SEATS_PER_ROW = 30;

    @Transactional
    void onStart(@Observes StartupEvent event) {
        long count = seatRepository.count();
        long target = (long) TARGET_ROWS * (long) SEATS_PER_ROW;
        if (count >= target) {
            return;
        }

        // If we already have seats but not enough, reset to keep a clean demo baseline.
        seatRepository.deleteAll();

        List<Seat> seats = new ArrayList<>((int) target);
        for (int r = 0; r < TARGET_ROWS; r++) {
            char row = (char) ('A' + r);
            double basePrice = basePriceForRow(r);
            for (int n = 1; n <= SEATS_PER_ROW; n++) {
                String seatId = row + String.valueOf(n);
                double price = basePrice + priceBumpForNumber(n);
                seats.add(new Seat(null, seatId, price, "FREE", null));
            }
        }

        seatRepository.persist(seats);
    }

    private static double basePriceForRow(int rowIdx) {
        // Rows near stage are more expensive.
        if (rowIdx <= 2) return 85.00;   // premium
        if (rowIdx <= 6) return 75.00;   // front
        if (rowIdx <= 12) return 65.00;  // mid
        return 55.00;                    // stalls
    }

    private static double priceBumpForNumber(int seatNumber) {
        // Center seats are slightly more expensive than edges.
        int center = (SEATS_PER_ROW + 1) / 2;
        int dist = Math.abs(seatNumber - center);
        if (dist <= 2) return 5.00;
        if (dist <= 6) return 2.50;
        return 0.00;
    }
}

