Rule: card-present payments in two different countries sooner than any flight between them allows. The rule and the
`travel` entries of getAlertTransactions use the same lower bound: the fastest flight between the two countries'
nearest airports, with no time on the ground. Countries connected by land (Singapore and Malaysia, for example) have
no bound and do not raise this alert.

- Read the verdict from `travel`; do not estimate flight times yourself.
  - IMPOSSIBLE: no one could have made the trip. A physical card cannot be in two countries at once, so one of the
    two payments was made with a cloned or counterfeit card. That is a strong fraud signal whatever the amounts.
  - POSSIBLE: the trip was not ruled out, but the bound is generous (no check-in, no boarding, no time to reach the
    airport). An elapsed time close to minimumMinutes is still very unlikely, so look for a reason in the data.
  - UNKNOWN: no flight data for one of the countries; judge from the elapsed time and the countries, and say so.
- Strengthening, when the travel is impossible or unexplained: the foreign merchant is new to the customer, the
  foreign amount is unusual, further foreign activity follows. None of these is required for impossible travel to
  be fraud. When a travel notice covers the country and the timing fits, a new merchant there and no earlier
  activity in that country are what a trip looks like: they do not add suspicion.
- Explaining it away needs a concrete reason in the data (a possible trip, the "foreign" merchant is one the
  customer regularly uses), not just a clean history.
- A travel notice names a country and a period. Weigh it with the order of the two payments and the travel verdict,
  not the countries alone:
  - at home first, then in the noticed country, and the trip POSSIBLE: that is the trip starting, and the notice
    explains the alert, even if its period began earlier that day or the day before;
  - a payment at home after the customer had already paid abroad, or too soon to have come back: the home
    payment is the suspicious one (a copy of the card used while they travel);
  - a country the notice does not name, or an IMPOSSIBLE trip: the notice explains nothing.
- Typical actions: block the card, contact the customer to confirm whether they hold the card and travelled.
- Regulations: card payments are largely outside the EUPG's duty and liability sections. Search only if
  a specific obligation is in question.
