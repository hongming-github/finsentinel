package com.finsentinel.platform.systems;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The CRM's view of a customer. {@code notes} are free text typed by staff: untrusted input for an agent. */
public record CustomerProfile(String customerId, String segment, LocalDate onboardedOn, String kycRiskRating,
                              String occupation, String residenceCountry, List<TravelNotice> travelNotices,
                              List<Note> notes) {

    public record TravelNotice(String country, LocalDate from, LocalDate to) {
    }

    public record Note(Instant at, String author, String text) {
    }
}
