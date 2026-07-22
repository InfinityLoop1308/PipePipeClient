# SABR compatibility profile delivery

PipePipe can update volatile WEB and MWEB SABR schemas without executing downloaded code. The
Extractor validates and compiles a signed declarative profile; the Android client only delivers,
caches, activates, and disables it.

## Build configuration

The profile endpoint and trust roots are build-time values:

- `SABR_COMPATIBILITY_PROFILE_PUBLIC_KEYS`: comma-separated
  `key-id=base64-raw-ed25519-public-key` entries. Up to eight keys can be embedded for rotation.
- `SABR_COMPATIBILITY_PROFILE_STABLE_URL`: HTTPS document endpoint for stable builds.
- `SABR_COMPATIBILITY_PROFILE_BETA_URL`: HTTPS document endpoint for beta builds.

Versions containing `-beta` select the `beta` channel; other versions select `stable`. Channel
caches and revision floors are independent. If a URL or the public keys are absent, PipePipe uses
the bundled native policy and does not schedule profile updates.

The endpoint must return the UTF-8 profile document with HTTP 200. HTTP 204 and 304 mean no
update. Transient status codes and I/O failures are retried by WorkManager. Redirects are disabled,
and the configured endpoint must use HTTPS.

## Activation and rollback

At startup, PipePipe restores the active and previous signed documents from private atomic files.
The highest accepted revision is persisted separately, so a damaged profile cache does not permit
an older downloaded revision. A cached document below that floor is accepted only when the cache
also records that the exact floor revision triggered the circuit breaker. Normal active, previous,
and circuit-breaker fallback generations use distinct restore paths.

A downloaded document is activated only after all of these steps succeed:

1. bounded JSON parsing;
2. Ed25519 verification against an embedded key ID;
3. validity, format, capability, and Extractor revision checks;
4. monotonic revision validation;
5. atomic cache and revision-floor persistence.

Each new SABR session snapshots the active immutable profile. If that profile fails while building
a request, interpreting a response, routing a demand, or decoding a media header, its session-local
circuit breaker switches directly to the bundled native policy. The same revision is disabled
globally for new sessions, which use the previous valid profile when one exists, while the revision
floor remains unchanged. Only a higher signed revision can replace it.

A valid partial document is not a profile failure. If it contains WEB but not MWEB, or MWEB but not
WEB, sessions for the omitted client use the bundled policy while the signed revision remains
active for the covered client.

Diagnostics contain revision numbers, action names, bounded counters, and failure classes. They do
not contain request bodies, response payloads, cookies, PO tokens, or media bytes.

## Validation

`SabrPolicyRuntimeTest` covers signature verification, key rotation, offline restoration,
anti-rollback state, cache corruption, invalid documents, and persisted circuit-breaker fallback.
`SabrPlaybackSmokeTest.compatibilityProfileReplaysShiftedUmpSchema` runs the native playback
harness against shifted request fields, UMP media part types, and media-header protobuf fields.
`SabrPlaybackSmokeTest.compatibilityProfileReplaysSabrDownloadPump` replays the same profile through
the multi-response pump used by SABR downloads and verifies the following-request template.
`SabrPlaybackSmokeTest.webOnlyProfileLeavesMwebReplayOnBuiltinPolicy` verifies that a partial WEB
profile leaves an MWEB session on the bundled policy without tripping the global circuit breaker.
`SabrProfileFallbackHarnessTest.profileMappingCanReuseBuiltinFieldWithDifferentWireType` verifies
that a shifted protobuf field can reuse an old number without failing the native baseline decoder.
`SabrPlaybackSmokeTest.signedCompatibilityProfilePlaysAndSeeks` performs real WEB or MWEB
extraction, renders video and audio, seeks, and verifies that the signed revision remains active.
`SabrPlaybackSmokeTest.signedBrokenProfileFallsBackToBuiltin` injects a required media-header
mapping failure into real playback and verifies that the revision is disabled while playback
continues with the bundled policy. `SabrProfileProcessRestartTest` verifies restoration across two
separate Android instrumentation processes.

The profile schema and native trust boundary are documented in PipePipeExtractor's
`SABR_COMPATIBILITY_PROFILES.md`.
