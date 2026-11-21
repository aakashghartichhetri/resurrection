# Security boundaries

This is a **single-operator demonstration**, not a multi-tenant service. Spring Security uses HTTP Basic with a server-side encoded password; React keeps the typed credentials only in memory. Production Compose routes browsers through Caddy HTTPS and does not publish PostgreSQL or the Java API ports. All workflow and metrics endpoints require `ROLE_OPERATOR`; readiness and liveness endpoints remain public for health probes. Fault injection and demo data are disabled in `compose.deploy.yaml`.

The API validates new workflow names, fault types, approval actions, and 64-character SHA-256 fingerprints. The response returns a sanitized `X-Request-Id`, and structured production logs include that request ID without logging credentials or request bodies. Replay metrics use `outcome` tags, never workflow IDs.

**Not implemented:** identity provider integration, MFA, per-user ownership, fine-grained roles, login rate limiting, secrets manager, tenant isolation, external audit-log anchoring, formal vulnerability scanning for Maven dependencies, or external security assessment. Use a unique production password and trusted HTTPS, restrict ingress to known operators, and do not expose the development Compose stack to the public internet. A generated npm lockfile and independent dependency review remain required before public deployment.
