# Email Sending — SMTP, Deliverability & the Notification Pipeline

> **Mental model:** sending email is two different problems wearing one name. (1) **The protocol problem** — speaking SMTP to hand a message to a mail server (easy, solved, shown from scratch below). (2) **The deliverability problem** — getting inbox placement at scale without being marked spam (hard, and the reason virtually every product uses SES/SendGrid/Postmark rather than its own mail servers). Interview designs ([notification system](../../03-high-level-design/notification-system/README.md)) are scored on the *pipeline around* sending: queueing, retries, idempotency, suppression, and webhooks.

---

## 1. SMTP in one diagram

```
App ──SMTP──► Your provider (SES/SendGrid)          ["submission", port 587 + STARTTLS, authenticated]
                    │  DNS lookup: MX record of recipient domain
                    ▼
              Recipient's mail server  ──► spam filtering ──► inbox / junk
```

The conversation is a line protocol from 1982, still verbatim today: `EHLO` → `MAIL FROM:` → `RCPT TO:` → `DATA` (headers + body, terminated by a lone `.`) → `QUIT`. Response codes: 2xx ok, 4xx **transient** (greylisting, mailbox busy — retry with backoff), 5xx **permanent** (no such user — never retry, suppress). That 4xx/5xx split is [retry discipline](../../07-microservices/resilience-patterns/README.md) applied to email — and mishandling it (retrying hard bounces) is how senders destroy their reputation.

## 2. Deliverability — why you buy, not build

- **Authentication trio (know all three):** **SPF** (DNS record listing IPs allowed to send for your domain), **DKIM** (cryptographic signature over headers/body, public key in DNS — proves integrity + domain ownership), **DMARC** (policy record: what receivers should do when SPF/DKIM fail — `p=quarantine/reject` — plus aggregate reports). Without all three configured, large receivers will junk or reject you.
- **Reputation:** receivers score sending IP + domain on bounce rate, spam complaints, volume patterns. New IPs must be **warmed up** (gradually increasing volume); high bounce rates (sending to bad addresses) crater reputation — which is why **suppression lists** (hard bounces, unsubscribes, complaints) are mandatory plumbing, not politeness.
- **This is why SES/SendGrid/Postmark win:** they own warmed IP pools, feedback loops with ISPs, bounce/complaint webhooks, and the compliance surface. Building your own MTA fleet (Postfix) is a specialized business decision, not a default.

## 3. The production pipeline (the interview answer)

```
Service ──"OrderShipped" event──► queue ──► Email worker ──► provider API/SMTP
                                              │ idempotency key = event id + template
                                              │ (redelivery must not re-send!)
                                              ├─ suppression check (bounced? unsubscribed?)
                                              ├─ template render (never string-concat HTML)
                                              └─ rate limit per provider quota
Provider webhooks (delivered/bounced/complained) ──► queue ──► update suppression + metrics
```

Email is **always async** ([queue](../../02-building-blocks/message-queues/README.md)) — never on the request path (a checkout must not fail because SMTP is slow). At-least-once delivery from the queue + [idempotency](../../08-api-design/idempotency/README.md) on `event_id` prevents the double-send. Webhooks close the loop: delivery/bounce/complaint events update suppression lists and drive [metrics](../../10-security-observability/observability/README.md) (delivery rate, bounce rate, complaint rate — the KPIs of this subsystem).

## 4. Installation / local testing

Never test against real inboxes. Run a **capture server**:

```bash
# Mailpit: SMTP server + web UI that captures everything
docker run -d -p 1025:1025 -p 8025:8025 axllent/mailpit
# Point your app at localhost:1025; view every "sent" email at http://localhost:8025
```

Real sending: verify your domain with SES/SendGrid, publish the SPF/DKIM/DMARC records they give you, then use SMTP creds (port 587, STARTTLS) or their REST API. Java options: **Jakarta Mail** (`org.eclipse.angus:angus-mail`) for SMTP, or the provider SDK.

## 5. The from-scratch implementation

[`EmailSender.java`](EmailSender.java) contains (1) **an SMTP client written on raw sockets** — the actual `EHLO/MAIL FROM/RCPT TO/DATA` dialogue with response-code parsing, so the protocol stops being a black box (run it against Mailpit and watch the conversation print); and (2) **a mini pipeline** — queue + idempotent worker + suppression list + transient-vs-permanent error handling with backoff. Zero dependencies.

## 6. Interview soundbites

- "Email is always async behind a queue with idempotency on the event ID — at-least-once redelivery must not become double-send."
- "4xx is transient — retry with backoff; 5xx is permanent — suppress and never retry: mixing those up burns your sender reputation."
- "SPF says who may send, DKIM signs what was sent, DMARC says what to do on failure — all three or the inbox says no."
- "We buy deliverability (SES/SendGrid) and keep the pipeline — suppression, webhooks, metrics — as our code."

**Related:** [Notification System](../../03-high-level-design/notification-system/README.md) · [Message Queues](../../02-building-blocks/message-queues/README.md) · [Idempotency](../../08-api-design/idempotency/README.md) · [SMS Sending](../sms-sending/README.md)
