# SABR cloud policy delivery

Release builds use the PipePipe policy repository by default. Deployments can override it with two
build environment variables:

- `SABR_POLICY_PUBLIC_KEY_BASE64`: raw 32-byte Ed25519 public key encoded as Base64.
- `SABR_POLICY_URL`: HTTPS endpoint serving the UTF-8 JSON document accepted by
  `SabrPolicyRuntime.installDocument`.

The production defaults are:

- Repository: `https://github.com/InfinityLoop1308/PipePipeSabrPolicies`
- Policy URL:
  `https://raw.githubusercontent.com/InfinityLoop1308/PipePipeSabrPolicies/main/policy.json`
- Raw Ed25519 public key: `Yyi5q4s0ikpgAitzw+62H8+ggPt+dTILJ0Of4vSIcrU=`

When both values are present, the client restores the last verified policy at startup, requests an
update immediately, and schedules a connected-network refresh every six hours. The endpoint must
return the signed policy document as the response body with HTTP 200. HTTP 204 is treated as no
update. Policies are signature checked, time bounded, and revision monotonic before activation.

The document is deliberately human-readable and suitable for publication in a public source
repository:

```json
{
  "format": 1,
  "revision": 1001,
  "validFromMs": 1784390400000,
  "validUntilMs": 1792166400000,
  "source": "function createSabrPolicy(sabr) { /* ... */ }",
  "signature": "base64-encoded Ed25519 signature"
}
```

The Ed25519 signature covers the canonical `SabrScriptPolicy` payload reconstructed from revision,
validity bounds, and source. JSON whitespace and key order are not signed and may be changed without
invalidating the document. Numeric metadata must use exact JSON integers; fractions, scientific
notation, strings, and values outside the signed 64-bit range are rejected rather than coerced. The
client keeps its verified cache in a private binary format; that cache is never downloaded or
executed as a binary.

If a policy throws while building requests, interpreting responses, routing demanded segments, or
decoding media headers, the client removes that cached policy, retains its highest revision to
prevent rollback, and uses the builtin policy until a higher signed revision is delivered.

Policies that declare `demand: true` can route reader-demand retries and decide how to handle exact
target omissions using bounded, payload-free returned segment identities. Policies without that
declaration retain the builtin demand behavior, so an older valid cloud policy remains compatible
with a client that supports the expanded contract. See the Extractor's
`SABR_JAVASCRIPT_POLICY.md` for the complete event and decision schema.
