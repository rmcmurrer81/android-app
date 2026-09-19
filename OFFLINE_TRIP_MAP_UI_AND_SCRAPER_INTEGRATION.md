# Offline Trip Map UI + Scraper Integration

The implementation is in:

```text
integration/offline-trip-map-kit/
```

Start with:

```text
integration/offline-trip-map-kit/README.md
```

It contains:

- a working dependency-free offline trip-map custom element;
- a Lovable/React wrapper;
- a trip-bundle builder that combines Sarah profile knowledge with `sarah-social-interest-v1`;
- recommendation ranking with visible explanations;
- plane, airport, hotel, ticket, transport, recommendation, emergency, and personal-note pins;
- IndexedDB persistence;
- a built-in schematic map that works without downloaded tiles;
- MapLibre/PMTiles/Mapbox adapter guidance for full offline street maps;
- current-data freshness and offline readiness;
- truthful integration opportunities for Stay22, Rove, Propellic, ElevenLabs, Lovable, Tavily, and AeroXplorer.

The module is UI-neutral and does not require the host to copy Sarah's Android activities.
