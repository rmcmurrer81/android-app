# Security and privacy boundary

## Credential rule

This source package is credential-free. A runtime host owns authenticated
transport and renewal. `RenewableAccessBoundary` exposes only state, expiry,
and renewal completion; it never gives Sarah code a reusable secret or service
address.

Protected voice receives public spoken text and a non-secret approved profile
identifier. The host performs the provider call. The integration result records
the actual route and timing without returning provider headers or credentials.

## Data minimization

- Use opaque person and record IDs; do not put names, email addresses, or
  precise coordinates in telemetry.
- Approximate current area expires and remains bound to one profile.
- Source receipts contain stable source IDs and scope, not cookies or session
  data.
- Wallet interfaces expose owner-approved display fields, not payment-card
  secrets or passwords.
- Logs may include request IDs, route enums, timing, and typed error codes.
  They must not include spoken private-mind content, credentials, or raw owner
  records.

## Failure behavior

- Missing, expired, or failed host access fails closed for online work.
- Local conversation remains independently available when the host supplies a
  local implementation.
- No half-configured endpoint/credential pair is assembled by this package.
- A timeout, cancellation, or provider failure is returned as a typed result;
  it is never converted into a false success.
- No integration method purchases, books, messages, calls, or publishes unless
  a separately reviewed host action contract explicitly implements it.

Run the supplied source and ZIP secret scans after every package change.
