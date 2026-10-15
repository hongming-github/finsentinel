package com.finsentinel.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a person could have got from one country to another in the time between two card-present payments.
 *
 * <p>The answer rests on a lower bound, never an estimate: the great-circle distance between the two countries' nearest
 * places with scheduled flights, flown at {@code maxSpeedKmh} with no time on the ground. Real trips are always slower
 * (taxiing, routes, boarding, getting to the airport), so "impossible" means impossible, and a trip the bound allows
 * may still be unlikely. Countries connected by land (a border, bridge or tunnel) get no bound at all, since the two
 * payments may be a few metres apart. A country without data is {@link Verdict#UNKNOWN}, never impossible.
 *
 * <p>Data: {@code travel/airports.csv} and {@code travel/land-links.csv}, built by {@code scripts/travel-data.py} from
 * OurAirports and GeoNames (sources and dates in the files).
 */
public final class TravelFeasibility implements Serializable {

    /** Mean Earth radius (IUGG); the spherical model is within 0.5% of the ellipsoid, small next to the speed margin. */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private final double maxSpeedKmh;

    /**
     * @param maxSpeedKmh the fastest average speed any trip could reach; set it above real airliner ground speeds,
     *                    since too low a speed would call real trips impossible
     */
    public TravelFeasibility(double maxSpeedKmh) {
        if (!(maxSpeedKmh > 0)) {
            throw new IllegalArgumentException("travel max speed must be positive");
        }
        this.maxSpeedKmh = maxSpeedKmh;
    }

    public enum Verdict {
        /** The elapsed time is at least the lower bound (always so for countries connected by land). */
        POSSIBLE,
        /** Faster than any flight between the two countries could be. */
        IMPOSSIBLE,
        /** One of the countries has no data; nothing can be said. */
        UNKNOWN
    }

    /**
     * @param minimum the lower bound on the trip; zero for countries connected by land, null when unknown
     * @param basis   how the bound was found, e.g. "nearest airports WSSS-EGLL, 10,880 km"
     */
    public record Check(String from, String to, Duration elapsed, Duration minimum, Verdict verdict, String basis) {
    }

    /**
     * @param elapsed time between the payment in {@code from} and the one in {@code to}; its sign is ignored
     */
    public Check check(String from, String to, Duration elapsed) {
        Duration gap = elapsed.abs();
        if (from.equals(to)) {
            return new Check(from, to, gap, Duration.ZERO, Verdict.POSSIBLE, "same country");
        }
        Data data = Data.bundled();
        String link = data.landLinks.get(Data.key(from, to));
        if (link != null) {
            return new Check(from, to, gap, Duration.ZERO, Verdict.POSSIBLE, "connected by land (" + link + ")");
        }
        Nearest nearest = data.nearest(from, to);
        if (nearest == null) {
            String missing = data.places.containsKey(from) ? to : from;
            return new Check(from, to, gap, null, Verdict.UNKNOWN, "no flight data for " + missing);
        }
        // Rounded down: a lower bound must not grow by rounding.
        Duration minimum = Duration.ofSeconds((long) (nearest.km / maxSpeedKmh * 3600));
        String basis = "nearest airports %s-%s, %,d km".formatted(nearest.identFor(from), nearest.identFor(to),
                Math.round(nearest.km));
        return new Check(from, to, gap, minimum, gap.compareTo(minimum) < 0 ? Verdict.IMPOSSIBLE : Verdict.POSSIBLE,
                basis);
    }

    /** The closest pair of places between two countries, the first ident in the first country of the key. */
    private record Nearest(String firstCountry, String firstIdent, String secondIdent, double km) {

        String identFor(String country) {
            return country.equals(firstCountry) ? firstIdent : secondIdent;
        }
    }

    private record Place(String ident, double latRad, double lonRad) {
    }

    /** The bundled tables, loaded once per class loader; nearest pairs are computed on first use and kept. */
    private static final class Data {

        private static volatile Data bundled;

        final Map<String, List<Place>> places;
        final Map<String, String> landLinks;
        private final Map<String, Nearest> nearest = new ConcurrentHashMap<>();

        private Data(Map<String, List<Place>> places, Map<String, String> landLinks) {
            this.places = places;
            this.landLinks = landLinks;
        }

        static Data bundled() {
            Data d = bundled;
            if (d == null) {
                synchronized (Data.class) {
                    d = bundled;
                    if (d == null) {
                        d = load();
                        bundled = d;
                    }
                }
            }
            return d;
        }

        static String key(String a, String b) {
            return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
        }

        /** Null when either country has no places. */
        Nearest nearest(String a, String b) {
            if (!places.containsKey(a) || !places.containsKey(b)) {
                return null;
            }
            String first = a.compareTo(b) < 0 ? a : b;
            String second = first.equals(a) ? b : a;
            return nearest.computeIfAbsent(key(a, b), k -> closestPair(first, second));
        }

        private Nearest closestPair(String first, String second) {
            Nearest best = null;
            for (Place p : places.get(first)) {
                for (Place q : places.get(second)) {
                    double km = greatCircleKm(p, q);
                    if (best == null || km < best.km) {
                        best = new Nearest(first, p.ident, q.ident, km);
                    }
                }
            }
            return best;
        }

        private static double greatCircleKm(Place p, Place q) {
            double dLat = q.latRad - p.latRad;
            double dLon = q.lonRad - p.lonRad;
            double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                    + Math.cos(p.latRad) * Math.cos(q.latRad) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
            return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(h)));
        }

        private static Data load() {
            Map<String, List<Place>> places = new HashMap<>();
            for (String[] row : rows("travel/airports.csv", 4)) {
                places.computeIfAbsent(row[0], c -> new ArrayList<>()).add(new Place(row[1],
                        Math.toRadians(Double.parseDouble(row[2])), Math.toRadians(Double.parseDouble(row[3]))));
            }
            Map<String, String> links = new HashMap<>();
            for (String[] row : rows("travel/land-links.csv", 3)) {
                links.put(key(row[0], row[1]), row[2]);
            }
            Map<String, List<Place>> frozen = new HashMap<>();
            places.forEach((country, list) -> frozen.put(country, List.copyOf(list)));
            return new Data(Map.copyOf(frozen), Map.copyOf(links));
        }

        /** Data rows of a bundled CSV: comment lines and the header skipped, no quoting (the build script emits none). */
        private static List<String[]> rows(String resource, int columns) {
            InputStream in = TravelFeasibility.class.getClassLoader().getResourceAsStream(resource);
            if (in == null) {
                throw new IllegalStateException("missing bundled resource " + resource);
            }
            List<String[]> rows = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                boolean header = true;
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (line.startsWith("#") || line.isBlank()) {
                        continue;
                    }
                    if (header) {
                        header = false;
                        continue;
                    }
                    String[] row = line.split(",", -1);
                    if (row.length != columns || !seen.add(line)) {
                        throw new IllegalStateException("bad or duplicate row in " + resource + ": " + line);
                    }
                    rows.add(row);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return rows;
        }
    }
}
