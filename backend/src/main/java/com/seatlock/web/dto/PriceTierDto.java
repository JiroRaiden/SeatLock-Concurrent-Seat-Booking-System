package com.seatlock.web.dto;

import com.seatlock.domain.PriceTier;

/**
 * @param priceMinor price in paise. Every money field in this API ends in
 *                   "Minor" so no client can mistake 45000 for forty-five
 *                   thousand rupees.
 * @param colour     hex colour for the seat map legend, carried from the
 *                   database so pricing and its visual encoding cannot drift
 *                   apart in a stylesheet
 */
public record PriceTierDto(Long id, String name, Long priceMinor, String colour) {
    public static PriceTierDto from(PriceTier tier) {
        return new PriceTierDto(tier.getId(), tier.getName(), tier.getPriceMinor(), tier.getColour());
    }
}
