# 0021. Geo-velocity: a deterministic lower bound on travel between countries

- Status: accepted
- Date: 2026-10-15

## Context

The GEO_JUMP rule fired on any two card-present payments in different countries within a fixed window (2 h). It knew
nothing about distance, so it was wrong in both directions:

- **False alarms** on trips that take minutes: Singapore to Johor Bahru across the Causeway, Copenhagen to Malmö.
- **Misses** on trips no flight can make: Singapore, then London five hours later (the flight takes about thirteen).

The agent had to judge feasibility itself from a list of rough flight times in the playbook. The phase 7 baseline
showed what that costs: `travel-notice-matches` failed 0/3 partly because the playbook described the rule wrongly and
the model reasoned about travel times on its own (fixed in `ea8c317`). A number a program can compute should not be
left to a model.

How issuers do it: card networks and issuers decide at authorization time with ML scores and rules; geo-velocity
(distance over time between consecutive card-present locations, against an airliner-speed limit) is a standard feature
there, combined with the POS entry mode (chip, contactless, magstripe fallback), merchant location and the app's
location. FinSentinel monitors after authorization, so the check is evidence for the rule and for the agent, not a
block. Major US issuers no longer ask for travel notices; the platform treats them as a weak signal.

## Decision

- **`TravelFeasibility` in `common`** (pure Java, no dependencies, used by the Flink rule and the platform): the
  minimum travel time between two countries is the great-circle distance between their **nearest places with
  scheduled flights**, at `max-speed-kmh` (1000), with **no time on the ground**. It is a lower bound, never an
  estimate: real trips add taxiing, routing, check-in, boarding and getting to the airport. So IMPOSSIBLE is certain;
  POSSIBLE only means not ruled out.
  - **Countries connected by land have no bound** (zero): a border, bridge or tunnel means the two payments can be
    metres apart. GeoNames borders, plus fixed links GeoNames does not list (Causeway, Channel Tunnel, Øresund, King
    Fahd Causeway, Hong Kong and Macau).
  - **No data is UNKNOWN, never impossible**: 18 GeoNames codes have no scheduled flights in the data, among them
    Ukraine (airspace closed), Andorra, Liechtenstein, San Marino, the Vatican, Palestine and uninhabited territories.
    A land border still gives zero (Poland to Ukraine is POSSIBLE).
  - Heliports and seaplane bases with scheduled service count as departure points: more points can only lower the
    bound, which keeps it safe.
- **Data in the repository**, built by `scripts/travel-data.py` from OurAirports (public domain) and GeoNames
  (CC BY 4.0), source and download date in each file: 4,327 places in 235 countries, 338 land links (100 KB).
  Re-run the script to refresh; nothing is downloaded at runtime.
- **The rule** (`RuleEngine.checkGeoJump`) alerts when the verdict is IMPOSSIBLE. For an UNKNOWN country it falls
  back to the old window (`rules.geo-jump.fallback-window`, 2 h). The reason names the bound and its basis
  ("card present in SG then GB 5h apart; the fastest possible trip takes 10h41m (nearest airports WSSL-EGET,
  10,698 km)"), or says the window was used. Out-of-order arrivals are compared by event time, and the reason and
  the transaction ids follow event time.
- **The agent** gets the same check as data: `getAlertTransactions` returns `{transactions, travel}`, one `travel`
  entry per consecutive pair of card-present alert payments in different countries (from, to, elapsed and minimum
  minutes, verdict, basis); no entry without a country change. The GEO_JUMP playbook drops its flight-time list and
  tells the agent to read the verdict, not to estimate.
- **No extra timing tolerance.** The bound already ignores at least half an hour of ground time on any real trip, and
  the transaction's event time is when the payment happened, by contract (`Transaction.eventTime`). A bank whose feed carries delayed
  posting times would need a tolerance here; ours does not.
- **Speed is configuration**, in both places: `rules.geo-jump.max-speed-kmh` (stream job) and
  `finsentinel.investigation.tools.travel-max-speed-kmh` (platform), equal so the rule and the agent agree.

### Why 1000 km/h

Too low a speed calls real trips impossible; too high a speed misses clones. Jet airliners cruise at about 900 km/h;
tailwinds push ground speed higher, and the fastest subsonic New York-London crossing (2020) averaged about 1,120 km/h
gate to gate. A payment cannot be made in flight, though: the last payment before departure and the first after
arrival add boarding and leaving the airport on top of the block time. The test fixture of fast real journeys passes
at 1000 km/h, record flight included, with the US-UK bound at 4 h 09 min against the record's 4 h 56 min.

## Evidence

- `TravelFeasibilityTest` (7 tests): seventeen fast real journeys (flights, the Batam, Kinmen, Macau and Tallinn
  ferries, Eurostar, the Øresund train, the Causeway) are all POSSIBLE both ways; Singapore to London in 5 h and 9 h,
  to Japan in 2 h, to Thailand in 20 min are IMPOSSIBLE; symmetric; land links zero; no data UNKNOWN.
- Bounds from Singapore: Malaysia 0 (Causeway), Indonesia 1 min 44 s (Batam, 29 km), Thailand 36 min (Narathiwat),
  Vietnam 52 min, Hong Kong 2 h 33 min, Australia 2 h 49 min (Karratha; Christmas Island has its own code, CX), Japan 3 h 16 min
  (Yonaguni), United States 9 h 09 min (Adak), United Kingdom 10 h 41 min.
- `RuleEngineTest`: six GEO_JUMP cases, five of which fail with the old window rule (mutation-checked: London 5 h
  later now alerts, Johor no longer does, the UNKNOWN fallback, the reason, out-of-order ordering).
- `InvestigationToolsTest`: the JSON the model sees carries the travel entry (SG to GB, 30 min elapsed, 641 minimum,
  IMPOSSIBLE); no entry without a country change; no minimum for UNKNOWN.
- Golden case `travel-notice-too-fast` (new): a travel notice for the right country, but London nine hours after
  Singapore. The old rule never raised it; a notice for the right country invites a model to clear it.
- Evaluation with the new playbook and `travel` field (`docs/eval/2026-10-15-geo-velocity.md`, gpt-5.4-mini, SINGLE):
  the seven GEO_JUMP cases x3 = **21/21**, tools 100%, $0.1435 ($0.0068 per run), p50 4.4 s / p95 12.5 s.
  `travel-notice-too-fast` 3/3 LIKELY_FRAUD (96-98). `travel-notice-matches` passed 3/3 but one run answered
  NEEDS_REVIEW at 48 (the run after the playbook fix in `ea8c317` had 3/3 LIKELY_LEGITIMATE at 10-15); passing runs
  keep no explanation, so the reason is not known. One run in three is no trend; watch it in the next full run.

## Consequences

- Fewer alerts for neighbouring countries, more for long impossible gaps. The simulator's GEO_JUMP scenario now puts
  the foreign payment 20 minutes after the home one (at 40 minutes Singapore to Thailand is within the bound).
- The bound is per country, not per city: two payments at the far ends of large countries (Russia, the US, Brazil)
  get the bound of the countries' nearest points. Merchant or terminal coordinates would tighten it; the event
  carries only the country.
- Land neighbours get no bound at all, so Malaysia to Thailand in one minute is not flagged. A border-distance table
  would help; cloned cards are more often used far away.
- Private jets from airfields without scheduled service are outside the data. Rare, and as fast as airliners.

## Next

- `posEntryMode` on the transaction (an optional Avro field with a default, the BACKWARD evolution path of ADR 0018),
  a cloned-card scenario with magstripe fallback, and a playbook line on chip-read pairs: two chip reads in an
  impossible pair point to the home payment being genuine and the far one a counterfeit, or vice versa.
- Use the bound in other rules (the first card-present payment abroad after a long gap is not suspicious by itself).
