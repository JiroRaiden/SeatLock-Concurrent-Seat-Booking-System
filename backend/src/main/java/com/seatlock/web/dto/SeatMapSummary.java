package com.seatlock.web.dto;

/** Counts for the header strip: "141 available". */
public record SeatMapSummary(long available, long booked, long blocked, long held) { }
