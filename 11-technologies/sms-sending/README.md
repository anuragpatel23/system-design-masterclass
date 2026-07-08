# SMS Sending — Provider APIs, Delivery Receipts & OTP Pipelines

> **Mental model:** unlike [email](../email-sending/README.md) (an open protocol you *could* speak yourself), SMS termination requires carrier interconnects — so in practice you **always** go through an aggregator (Twilio, Vonage, AWS SNS, MSG91, Kaleyra…) via REST API. The engineering is therefore entirely about the **pipeline**: async sending, idempotency, delivery-receipt webhooks, provider failover, rate/cost control, and the OTP flow — which is the #1 context interviews ask about ("design login with OTP", "design 2FA").

---

## 1. What happens when you "send an SMS"

```
App ──HTTPS POST──► Provider API ──SMPP──► Carrier(s) ──► handset
     {to, from, body}    │ returns message_id + status "queued" IMMEDIATELY
                         ▼ (seconds to minutes later)
     Your webhook ◄── delivery receipt (DLR): delivered / undelivered / failed
```

- **Sending is asynchronous by nature:** the API accepting your request means "queued," not "delivered." Real delivery status arrives later via **DLR webhooks** (or polling). Any design that treats the API 200 as delivery is wrong — the status field on your `sms_messages` table is a *state machine*: `QUEUED → SENT → DELIVERED | FAILED`.
- **Sender identity is regulated and per-country:** long codes, short codes, alphanumeric sender IDs (no replies!), and mandatory sender registration (e.g., 10DLC in the US, DLT registration in India). "We'll just send from any number" is a compliance failure — knowing this exists is the signal; the details vary by country.
- **Message mechanics:** 160 chars per GSM-7 segment (70 for Unicode — one emoji converts the whole message to UCS-2 and *triples your cost*); longer texts split into segments, billed each. Cost is per-segment per-country and varies ~50x by destination — which is why cost monitoring and per-user rate caps are real design requirements, not nice-to-haves ([rate limiting](../../02-building-blocks/rate-limiting/README.md) protects your wallet here, not your servers).

## 2. The OTP pipeline (the interview standard)

- Generate a 6-digit code, store **hash(code)** with TTL 5min + attempt counter (max 3–5 verify attempts — brute-force protection), rate-limit *generation* per phone/IP (SMS-pumping fraud is a real, expensive attack — attackers trigger OTPs to premium numbers they profit from; velocity checks + geo blocks are the defense).
- Send async via [queue](../../02-building-blocks/message-queues/README.md); worker is [idempotent](../../08-api-design/idempotency/README.md) on event ID.
- **Multi-provider failover:** providers have regional outages and per-country quality differences; the standard design is an abstraction over 2+ providers with health-based routing ([circuit breaker](../../07-microservices/resilience-patterns/README.md) per provider, fallback to the secondary) — plus DLR-driven quality scoring per provider per country.
- Retry discipline mirrors email: provider 5xx/timeout = retry with backoff (idempotently!); "invalid number" = permanent, don't retry; undelivered DLR after N minutes = optional failover resend via the other provider (dedup on your side!).

## 3. Installation / try it

No real credentials needed to learn the shape — the Java file below runs a **mock provider server locally**. For the real thing: create a Twilio trial account, get a number, then it's one HTTPS call:

```bash
curl -X POST https://api.twilio.com/2010-04-01/Accounts/$SID/Messages.json \
  --data-urlencode "To=+91..." --data-urlencode "From=+1..." \
  --data-urlencode "Body=Your code is 424242" -u $SID:$AUTH_TOKEN
```

Java: plain `java.net.http.HttpClient` (shown below — you don't need an SDK to call a REST API) or `com.twilio.sdk:twilio`.

## 4. The from-scratch implementation

[`SmsService.java`](SmsService.java) implements the full pipeline with zero dependencies: **a mock provider HTTP server** (built on the JDK's `HttpServer`, returning message IDs and posting delayed delivery receipts back), and a client stack with **idempotent async sending, per-phone rate limiting, OTP generate/verify with hashing + attempt caps, multi-provider failover on error, and a DLR webhook handler** driving the message state machine. Run it and watch a message go `QUEUED → DELIVERED`.

## 5. Interview soundbites

- "The provider API's 200 means *queued* — delivery truth arrives via DLR webhooks, so message status is a state machine, not a boolean."
- "OTP: store the hash with a 5-minute TTL and an attempt cap; rate-limit generation per phone and IP because SMS-pumping fraud is an attack on your budget."
- "Multi-provider with per-country health scoring and circuit-breaker failover — SMS quality is regional and providers do go down."
- "One emoji flips GSM-7 to UCS-2 and triples segment count — cost control is a design requirement in SMS systems."

**Related:** [Email Sending](../email-sending/README.md) · [Notification System](../../03-high-level-design/notification-system/README.md) · [Rate Limiting](../../02-building-blocks/rate-limiting/README.md) · [Resilience Patterns](../../07-microservices/resilience-patterns/README.md)
