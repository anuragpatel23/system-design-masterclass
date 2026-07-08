# Security Essentials — Encryption, Secrets, OWASP, Defense in Depth

> **The question this answers, precisely:** when the interviewer says "how would you secure this system?", what is the structured, mechanism-aware answer that goes beyond "we'll use HTTPS and hash passwords"? The organizing idea is **defense in depth along the request path**: trace a request from the internet to the database and name the control at each boundary — an answer *shape* that scales to any system you've just designed.

---

## 1. Encryption — in transit, at rest, and the key problem

- **In transit — TLS everywhere, including inside the datacenter.** The modern posture is **zero trust**: "inside the perimeter" is not a trust level, because one compromised box otherwise reads everything east-west. Externally: TLS 1.2+/1.3 at the [gateway/LB](../../02-building-blocks/api-gateway/README.md); internally: [mTLS via the service mesh](../../07-microservices/service-mesh/README.md), which also gives workload *identity*. Know the one-sentence mechanics: asymmetric crypto (certificates) authenticates the server and establishes a shared secret; symmetric crypto (AES-GCM) encrypts the stream — asymmetric for handshake, symmetric for bulk, because it's ~1000x faster.
- **At rest — layered:** disk/volume encryption (protects against physical theft, nothing else — the running DB decrypts transparently), database/field-level encryption for the crown jewels (SSNs, card numbers — protects against DB compromise and curious insiders, at the cost of losing indexes/queries over those fields), and application-level encryption where the service encrypts before storing (strongest, most operationally expensive).
- **The real problem is key management** — encryption relocates the secret; it doesn't remove it. The standard answer: a **KMS** (AWS KMS/Vault) holding a **master key** (in HSMs, never exported) that encrypts **data keys** ("envelope encryption": data encrypted with a data key; the data key stored *encrypted by the KMS* next to the data; decryption asks the KMS to unwrap it). What this buys: **rotation** (rewrap data keys, not re-encrypt petabytes), **audit** (every decrypt is a logged KMS call), **revocation** (cut a service's KMS access, its data goes dark). Naming envelope encryption converts "we encrypt at rest" from a checkbox to a mechanism.
- **Passwords are their own category:** never encrypted (reversible = wrong), only hashed with **slow, salted, memory-hard functions** (argon2/bcrypt) — slowness is the feature: it turns a billions/sec GPU guessing rate into thousands/sec; salts kill rainbow tables and make identical passwords hash differently.

## 2. Secrets management — the boring gap that causes real breaches

Config values that *are* secrets (DB passwords, API keys, signing keys) must not live in code, images, or env-var dumps. The pattern: a secrets manager (Vault/AWS Secrets Manager) + **short-lived, dynamically issued credentials** (a service gets a DB credential valid for hours, auto-rotated — leaked credentials expire on their own) + workload identity (the service authenticates to the vault via its platform identity, not… another secret). Mention **secret scanning in CI** (leaked-key-in-git is a top real-world incident cause) and you sound like you've done incident review.

## 3. The attack classes worth knowing cold (OWASP, prioritized for interviews)

- **Injection (SQLi):** user input concatenated into queries. Fix: **parameterized queries always** — the query's code/data boundary is fixed at prepare time. (Same family: command injection, template injection.)
- **Broken object-level authorization (BOLA/IDOR)** — the #1 API vulnerability; covered mechanically in [AuthN/AuthZ](../authentication-authorization/README.md): valid token, missing per-object ownership check.
- **XSS:** untrusted content rendered as HTML/JS steals sessions. Fix: contextual output encoding (framework auto-escaping), Content-Security-Policy, HttpOnly cookies (token theft resistance).
- **CSRF:** the browser auto-attaches cookies, so a hostile page can make state-changing requests as you. Fix: SameSite cookies + anti-CSRF tokens. (Note the duality: cookie-based auth needs CSRF defense; header-token auth needs XSS defense — [the trade from AuthN/AuthZ](../authentication-authorization/README.md).)
- **SSRF:** your server fetches a user-supplied URL → attacker points it at internal metadata endpoints (`169.254.169.254` — the classic cloud-credential theft). Fix: egress allowlists, metadata-service protections. Naming SSRF for any "fetch the user's URL" feature ([URL shortener](../../03-high-level-design/url-shortener/README.md) previews!) is a strong, current signal.
- **DoS at the application layer:** unbounded page sizes, [unbounded GraphQL queries](../../08-api-design/grpc-graphql-rest/README.md), regex backtracking — the fix is resource bounds everywhere ([rate limiting](../../02-building-blocks/rate-limiting/README.md), query cost limits, timeouts), which you've already designed if you followed sections 02/07.

## 4. Defense in depth — the request-path answer shape

```
Internet ─► Edge (DDoS scrubbing, WAF, TLS termination)
         ─► Gateway (authN, coarse authZ, rate limits, input size caps)
         ─► Service (input validation, object-level authZ, parameterized queries)
         ─► Mesh (mTLS, workload identity, network policy: orders may call payments — nothing else may)
         ─► Data (envelope encryption, field-level for crown jewels, least-privilege DB accounts)
         ─► Everywhere: audit logs, secret rotation, patched dependencies
```

Each layer assumes the previous one failed. Two principles to name as you walk it: **least privilege** (every identity — human, service, DB account — gets the minimum; the breach's blast radius is whatever the compromised identity could touch) and **fail closed** for security controls (authz service down ⇒ deny — the deliberate inverse of the availability-biased [fail-open](../../07-microservices/resilience-patterns/README.md) default for auxiliary systems).

## 5. Common pitfalls

- "We use HTTPS and hash passwords" as the whole answer — no internal traffic story, no key management, no authz.
- Disk encryption presented as protecting against hackers — it protects against stolen disks; a compromised app reads decrypted data all day.
- Building your own crypto/session/auth primitives — the correct senior instinct is "boring, audited, standard."
- No secrets story — DB password in an env var in a Dockerfile in git.
- Security as a final slide rather than per-boundary controls — the request-path walk *is* the differentiator.

## 6. 60-Second Interview Answer

> "I structure security as defense in depth along the request path, each layer assuming the one before it failed. At the edge: DDoS protection, WAF, TLS 1.3. At the gateway: authentication, coarse authorization, rate limiting, input size caps. In the service: input validation, parameterized queries — which kills injection structurally — and object-level authorization, because BOLA, a valid token accessing someone else's object, is the top real-world API vulnerability. East-west, zero trust: mTLS with workload identity from the service mesh, plus network policy so only declared service pairs can talk. At the data layer, envelope encryption — data keys wrapping the data, a KMS-held master key wrapping the data keys — which is what makes rotation, audit, and revocation tractable; field-level encryption for crown jewels; and passwords only ever as salted argon2 or bcrypt hashes, where slowness is the point. Secrets live in a vault with short-lived dynamic credentials, never in code or images. And two cross-cutting principles: least privilege for every identity so a breach's blast radius is bounded, and security controls fail closed — if the authz service is down, we deny — the deliberate opposite of the fail-open default for availability-oriented auxiliaries."

**Related:** [Authentication & Authorization](../authentication-authorization/README.md) · [API Gateway](../../02-building-blocks/api-gateway/README.md) · [Service Mesh](../../07-microservices/service-mesh/README.md) · [Rate Limiting](../../02-building-blocks/rate-limiting/README.md)
