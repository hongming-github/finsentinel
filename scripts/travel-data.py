#!/usr/bin/env python3
"""Builds the data behind TravelFeasibility (common/src/main/resources/travel/) from two public sources.

Download them first (both are small to medium plain-text files):
  OurAirports, public domain:  https://davidmegginson.github.io/ourairports-data/airports.csv
  GeoNames, CC BY 4.0:         https://download.geonames.org/export/dump/countryInfo.txt

Usage: python3 -I scripts/travel-data.py <airports.csv> <countryInfo.txt> <downloaded-on YYYY-MM-DD>
"""
import csv
import pathlib
import sys

# Facility types that carry scheduled passengers. Heliports and seaplane bases count: they are departure points too
# (Monaco's heliport, the Maldives' resort seaplanes), and more departure points only lower the bound.
TYPES = {"large_airport", "medium_airport", "small_airport", "heliport", "seaplane_base"}

# Land crossings GeoNames does not list as borders: bridges, tunnels and the land borders of Hong Kong and Macau.
FIXED_LINKS = [
    ("MY", "SG", "Johor-Singapore Causeway and Second Link"),
    ("FR", "GB", "Channel Tunnel"),
    ("DK", "SE", "Oresund Bridge"),
    ("BH", "SA", "King Fahd Causeway"),
    ("CN", "HK", "land border"),
    ("CN", "MO", "land border"),
    ("HK", "MO", "Hong Kong-Zhuhai-Macau Bridge"),
]


def main(airports_csv, country_info, downloaded_on):
    out = pathlib.Path(__file__).resolve().parent.parent / "common/src/main/resources/travel"
    out.mkdir(parents=True, exist_ok=True)

    with open(airports_csv, encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f)
                if r["scheduled_service"] == "yes" and r["type"] in TYPES and len(r["iso_country"]) == 2]
    rows.sort(key=lambda r: (r["iso_country"], r["ident"]))
    with open(out / "airports.csv", "w", encoding="utf-8", newline="") as f:
        f.write(f"""# Places with scheduled passenger flights, by country: where the minimum-travel-time bound (TravelFeasibility)
# measures from. Derived from OurAirports airports.csv (public domain, https://ourairports.com/data/), downloaded
# {downloaded_on}, by scripts/travel-data.py, coordinates rounded to 0.001 degree (about 100 m). Types with
# scheduled_service=yes: {', '.join(sorted(TYPES))}.
country,ident,latitude,longitude
""")
        for r in rows:
            f.write(f"{r['iso_country']},{r['ident']},{float(r['latitude_deg']):.3f},{float(r['longitude_deg']):.3f}\n")

    countries = {}
    with open(country_info, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            cols = line.rstrip("\n").split("\t")
            countries[cols[0]] = [n for n in cols[17].split(",") if n]
    pairs = {}
    for country, neighbours in countries.items():
        for n in neighbours:
            pairs[tuple(sorted((country, n)))] = "border"
    for a, b, link in FIXED_LINKS:
        if tuple(sorted((a, b))) in pairs:
            sys.exit(f"{a}-{b} is already a GeoNames border; drop it from FIXED_LINKS")
        pairs[tuple(sorted((a, b)))] = link
    with open(out / "land-links.csv", "w", encoding="utf-8", newline="") as f:
        f.write(f"""# Countries connected by land (a border, a bridge or a tunnel): the bound between them is zero. Borders from GeoNames
# countryInfo.txt neighbours (CC BY 4.0, https://www.geonames.org/), downloaded {downloaded_on}, made symmetric;
# fixed links GeoNames does not list are added by scripts/travel-data.py and named in the third column.
country,neighbour,link
""")
        for (a, b), link in sorted(pairs.items()):
            f.write(f"{a},{b},{link}\n")

    print(f"{len(rows)} places in {len({r['iso_country'] for r in rows})} countries, {len(pairs)} land links")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
