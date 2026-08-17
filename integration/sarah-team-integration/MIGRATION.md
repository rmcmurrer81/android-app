# Migration and compatibility

This is an adapter boundary, not a database migration utility. Connect it to a
copy or test profile before exposing it to an owner-facing build.

## Required migration order

1. Preserve the current app and create a recoverable owner-data backup with its
   existing tooling.
2. Inventory profile IDs and reject ambiguous or missing person scope.
3. Map read-only list/get operations first.
4. Validate conversation route and source-receipt truth.
5. Enable writes one domain at a time: trips, calendar, then wallet.
6. Enable protected voice only after the host can return an approved route
   receipt and cancel superseded requests.
7. Keep the old host available until physical Android/Windows acceptance.

## Data rules

- Do not renumber or merge people from display-name similarity.
- Do not copy authentication, provider credentials, voice credentials, Gmail
  tokens, private-mind records, photos, or caches through this package.
- Use append-only correction/history behavior where the native store already
  provides it.
- A missing field remains unknown; it is not filled from another profile.
- Calendar, ticket, loyalty, and wallet records remain profile-isolated.
- Opening a destination or Workbench section is navigation, not proof of a
  search, booking, purchase, call, reminder, or completed trip.

## Version negotiation

The host must reject an unknown major `schemaVersion`. New optional fields may
be ignored only when their omission cannot change person scope, route truth,
source truth, authorization, or an external action.
