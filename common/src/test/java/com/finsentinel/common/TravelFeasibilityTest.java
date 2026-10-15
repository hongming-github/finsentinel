package com.finsentinel.common;

import com.finsentinel.common.TravelFeasibility.Check;
import com.finsentinel.common.TravelFeasibility.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TravelFeasibilityTest {

    /** The speed in stream-job.properties and application.yaml. */
    private final TravelFeasibility travel = new TravelFeasibility(1000);

    /**
     * Fast real journeys, approximate times from published timetables (and one record flight). The bound must allow
     * every one of them: a lower bound that exceeds a real trip would raise alerts on genuine travellers.
     */
    private record Journey(String from, String to, Duration fastest, String how) {
    }

    private static final List<Journey> REAL_JOURNEYS = List.of(
            new Journey("SG", "MY", Duration.ofMinutes(15), "Woodlands checkpoint to Johor Bahru by car"),
            new Journey("SG", "ID", Duration.ofMinutes(50), "HarbourFront to Batam by ferry"),
            new Journey("SG", "TH", Duration.ofMinutes(90), "Singapore to Phuket by air"),
            new Journey("SG", "VN", Duration.ofMinutes(110), "Singapore to Ho Chi Minh City by air"),
            new Journey("SG", "HK", Duration.ofMinutes(210), "Singapore to Hong Kong by air"),
            new Journey("SG", "AU", Duration.ofMinutes(285), "Singapore to Perth by air"),
            new Journey("SG", "JP", Duration.ofMinutes(360), "Singapore to Tokyo by air"),
            new Journey("SG", "GB", Duration.ofMinutes(750), "Singapore to London by air"),
            new Journey("US", "GB", Duration.ofMinutes(296), "New York to London, record subsonic crossing (2020)"),
            new Journey("GB", "FR", Duration.ofMinutes(136), "London to Paris by Eurostar"),
            new Journey("DK", "SE", Duration.ofMinutes(35), "Copenhagen to Malmo by train"),
            new Journey("HK", "MO", Duration.ofMinutes(55), "Hong Kong to Macau by ferry"),
            new Journey("CN", "TW", Duration.ofMinutes(30), "Xiamen to Kinmen by ferry"),
            new Journey("FI", "EE", Duration.ofMinutes(120), "Helsinki to Tallinn by ferry"),
            new Journey("KR", "JP", Duration.ofMinutes(55), "Busan to Fukuoka by air"),
            new Journey("AE", "QA", Duration.ofMinutes(70), "Dubai to Doha by air"),
            new Journey("AU", "NZ", Duration.ofMinutes(170), "Brisbane to Auckland by air"));

    @Test
    void neverCallsARealJourneyImpossible() {
        for (Journey j : REAL_JOURNEYS) {
            Check there = travel.check(j.from(), j.to(), j.fastest());
            Check back = travel.check(j.to(), j.from(), j.fastest());
            assertThat(there.verdict()).as(j.how() + ": " + there).isEqualTo(Verdict.POSSIBLE);
            assertThat(back.verdict()).as(j.how() + " (return): " + back).isEqualTo(Verdict.POSSIBLE);
        }
    }

    @Test
    void cardInTwoDistantCountriesSoonerThanAnyFlightIsImpossible() {
        assertThat(travel.check("SG", "GB", Duration.ofHours(5)).verdict()).isEqualTo(Verdict.IMPOSSIBLE);
        assertThat(travel.check("SG", "JP", Duration.ofHours(2)).verdict()).isEqualTo(Verdict.IMPOSSIBLE);
        assertThat(travel.check("SG", "TH", Duration.ofMinutes(20)).verdict()).isEqualTo(Verdict.IMPOSSIBLE);
        // The old two-hour window let these through.
        assertThat(travel.check("SG", "GB", Duration.ofHours(9)).verdict()).isEqualTo(Verdict.IMPOSSIBLE);
        assertThat(travel.check("SG", "US", Duration.ofHours(8)).verdict()).isEqualTo(Verdict.IMPOSSIBLE);
    }

    @Test
    void namesTheBoundAndHowItWasFound() {
        Check check = travel.check("SG", "GB", Duration.ofHours(5));

        assertThat(check.minimum()).isBetween(Duration.ofHours(10), Duration.ofHours(11));
        assertThat(check.basis()).matches("nearest airports WS\\w\\w-EG\\w\\w, 10,\\d{3} km");
        assertThat(check.elapsed()).isEqualTo(Duration.ofHours(5));
    }

    @Test
    void isSymmetricAndIgnoresTheOrderOfThePayments() {
        Check there = travel.check("SG", "JP", Duration.ofHours(3));
        Check back = travel.check("JP", "SG", Duration.ofHours(-3));

        assertThat(back.minimum()).isEqualTo(there.minimum());
        assertThat(back.elapsed()).isEqualTo(Duration.ofHours(3));
        assertThat(back.verdict()).isEqualTo(there.verdict());
        // Each airport stays with its own country.
        assertThat(there.basis()).contains("WS");
        assertThat(back.basis()).startsWith("nearest airports RO");
    }

    @Test
    void countriesConnectedByLandHaveNoBound() {
        // A GeoNames border, and fixed links GeoNames does not list.
        for (String[] pair : new String[][] {{"MY", "TH"}, {"SG", "MY"}, {"GB", "FR"}, {"HK", "CN"}}) {
            Check check = travel.check(pair[0], pair[1], Duration.ofSeconds(1));
            assertThat(check.minimum()).isZero();
            assertThat(check.verdict()).isEqualTo(Verdict.POSSIBLE);
            assertThat(check.basis()).startsWith("connected by land");
        }
        assertThat(travel.check("SG", "SG", Duration.ZERO).basis()).isEqualTo("same country");
    }

    @Test
    void aCountryWithoutDataIsUnknownNeverImpossible() {
        // Ukraine has no scheduled flights in the data; ZZ is no country.
        for (String other : List.of("UA", "ZZ")) {
            Check check = travel.check("SG", other, Duration.ofSeconds(1));
            assertThat(check.verdict()).isEqualTo(Verdict.UNKNOWN);
            assertThat(check.minimum()).isNull();
            assertThat(check.basis()).isEqualTo("no flight data for " + other);
        }
        // A land border still counts without flight data.
        assertThat(travel.check("PL", "UA", Duration.ofSeconds(1)).verdict()).isEqualTo(Verdict.POSSIBLE);
    }

    @Test
    void aFasterSpeedOnlyLowersTheBound() {
        Duration slow = new TravelFeasibility(800).check("SG", "AU", Duration.ZERO).minimum();
        Duration fast = new TravelFeasibility(1200).check("SG", "AU", Duration.ZERO).minimum();

        assertThat(fast).isLessThan(slow);
        assertThatThrownBy(() -> new TravelFeasibility(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
