package com.seatlock.web.dto;

import java.util.List;

/**
 * Seats are returned grouped into rows, already sorted, rather than as a flat
 * list the client has to bucket itself. The server knows the layout; making 
 * every client re-derive it is duplicated logic that will eventually disagree.
 */
public record SeatMapRow(
        String rowLabel,
        Short rowIndex,
        String section,
        List<SeatMapSeat> seats
) { }
