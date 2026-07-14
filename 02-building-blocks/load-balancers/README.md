# Load Balancers

> A load balancer distributes incoming requests across a pool of backend servers so no single server is overwhelmed, and so the failure of any one server doesn't take down the whole service. It's usually the very first "building block" traffic hits after DNS resolution, and it's where availability (health checks) and scalability (distribution) physically meet. This guide goes end-to-end: where a load balancer sits in the network stack, the hardware/software/cloud-managed spectrum (a gap that trips up even experienced engineers), every major algorithm with the math behind it, SSL termination modes, DNS-level and global load balancing, and vendor-specific deep dives (AWS, GCP, Azure, NGINX, HAProxy, Envoy).

---

## 1. What a Load Balancer Actually Is: A Reverse Proxy

Before the type taxonomy, one architectural fact that clarifies everything else: **a load balancer is a specialized reverse proxy.**

- A **forward proxy** sits in front of *clients*, hiding them from the servers they talk to (corporate proxies, VPNs, "the internet thinks you're one IP").
- A **reverse proxy** sits in front of *servers*, hiding them from the clients that talk to them. The client thinks it's talking to one thing; a fleet of real servers sits behind it. A load balancer is a reverse proxy whose primary job is *distribution* — but the same position in the network is also where you'd do TLS termination, caching, compression, and request routing, which is why NGINX/HAProxy/Envoy are simultaneously "the load balancer," "the reverse proxy," and often "the API gateway" in smaller architectures ([API Gateway](../api-gateway/README.md) is the specialization of this role with auth/rate-limiting baked in).

**Where it sits, mapped to the OSI model** (see [Networking Fundamentals](../../08-api-design/networking-fundamentals/README.md) for the full OSI/TCP/UDP treatment): a load balancer operates at **Layer 3** (IP, rare — Gateway LBs), **Layer 4** (TCP/UDP, transport), or **Layer 7** (HTTP/gRPC, application) — never Layer 1/2, and it's this layer choice, not the vendor, that determines what the LB *can even see* about a request.

---

## 2. Layer 4 vs Layer 7 Load Balancing — the Mechanics, Not Just the Label

### Layer 4 (Transport layer — TCP/UDP)

Routes based on the **5-tuple** (source IP, source port, destination IP, destination port, protocol) — **without ever parsing the payload**. Two implementation styles matter:

- **NAT mode (most common):** the LB terminates the client's TCP connection and opens a *new* TCP connection to the chosen backend, rewriting IP/port headers as packets flow through — the LB is in the data path for the life of the connection.
- **Direct Server Return (DSR) / transparent mode:** the LB forwards the packet (rewriting only the destination MAC, not the IP) to the backend, but the **backend replies directly to the client**, bypassing the LB on the return path. This is how you get multi-terabit throughput out of a single LB — it never has to shovel response bytes, only route the (usually much smaller) request. This is the technique behind Google's **Maglev** (§9) and most hardware L4 appliances.

L4 has **no idea a request is even HTTP** — it cannot see a URL path, a header, or a cookie. That's simultaneously its limitation and its superpower: because there's nothing to parse, it's protocol-agnostic (works for HTTP, gRPC, raw databases, game servers, MQTT, anything over TCP/UDP) and can run at line rate.

- **Examples:** AWS Network Load Balancer (NLB), LVS/IPVS (Linux Virtual Server), F5 BIG-IP in L4 mode, HAProxy in TCP mode.

### Layer 7 (Application layer — HTTP/HTTPS/gRPC)

**Terminates** the client connection completely (full TCP handshake, and for HTTPS a full TLS handshake), reads and parses the actual request — method, path, headers, cookies, even body — makes a routing decision, then opens a **separate connection** to the chosen backend (optionally re-encrypted — see §11) and proxies the response back.

- **Pros:** content-based routing (`/api/*` → service A, `/static/*` → CDN-origin, `Host: mobile.example.com` → a different backend fleet), can terminate TLS centrally, can inject/strip headers, can do cookie-based sticky sessions, can retry a failed request against a *different* backend transparently (impossible at L4 once a TCP connection is pinned), can do request/response transformation, WAF-style inspection, and A/B or canary routing by header or weighted rule.
- **Cons:** strictly slower per-request than L4 (parsing cost, an extra full connection setup to the backend, and for TLS a second decrypt/re-encrypt cycle if bridging), and protocol-specific — it must understand HTTP/1.1, HTTP/2, or gRPC framing to do its job, so a new protocol means new LB support.
- **Examples:** AWS Application Load Balancer (ALB), NGINX, HAProxy in HTTP mode, Envoy, Traefik.

**Senior-level nuance interviewers probe for:** "Use an L7 load balancer" as a blanket answer is a mild red flag if the workload is, say, a raw database connection pool, an internal gRPC-streaming backbone needing sub-millisecond overhead, or a UDP game server — there L4 is not just adequate but *necessary*, since there's no HTTP semantics to route on and the extra L7 overhead is pure waste. Real architectures commonly **layer them**: an L4 NLB in front for static IPs/DDoS absorption/extreme throughput, forwarding to an L7 ALB for content routing (this exact NLB→ALB chaining pattern is a documented AWS reference architecture, precisely because ALB alone can't offer static IPs).

---

## 3. Hardware Load Balancers

A **hardware load balancer** is a dedicated physical network appliance — purpose-built silicon (custom ASICs/FPGAs alongside general CPUs) whose entire job is moving and terminating connections at wire speed.

**Representative products:** F5 BIG-IP (the long-standing enterprise incumbent), Citrix ADC (formerly NetScaler), A10 Networks Thunder ADC, Radware Alteon. (Cisco's ACE line, once common, is end-of-life — a useful data point about how even "hardware forever" markets get disrupted by software/cloud LBs.)

**How they earn their keep:**
- **Dedicated SSL/TLS offload silicon** — cryptographic accelerator chips do RSA/ECC handshakes and bulk encryption far faster per-watt than general-purpose CPUs, which matters when terminating hundreds of thousands of concurrent HTTPS connections.
- **Custom ASICs for packet processing** — line-rate L4 forwarding (often DSR-style, see §2) at throughput levels (multi-Tbps aggregate) that are difficult to match with commodity x86 boxes without a large fleet.
- **Bundled enterprise features** — WAF, GSLB (§8), DDoS mitigation, and compliance certifications (FIPS 140-2, Common Criteria) baked into one appliance with one vendor support contract — attractive to regulated industries that need a single throat to choke.

**The real trade-offs:**
- **Capital expense and lead time.** You buy (or lease) physical boxes; capacity is fixed at purchase time. Scaling means buying more hardware and racking it — weeks to months, not seconds. This is the single biggest reason cloud-native architectures have moved away from them.
- **Not elastic.** A traffic spike beyond the box's rated capacity has no "auto-scale" answer short of pre-provisioning for peak (expensive, wasteful) or manual failover to a bigger unit.
- **Active-passive or active-active pairing required for HA** — a single appliance is a single point of failure, so production deployments run pairs with a heartbeat protocol (e.g., VRRP) for failover, itself an operational burden (state sync, split-brain risk).
- **Vendor lock-in and licensing cost** — configuration is often proprietary (F5's iRules/TMOS vs. a portable NGINX config file), and licenses can scale with throughput, making cost less predictable than "pay for the VM you use."
- **Doesn't fit ephemeral infrastructure** — containers and auto-scaling groups spin instances up/down by the minute; a physical box configured via a vendor GUI or CLI doesn't reconcile with that model the way a software LB's API/config-as-code does.

**When they're still the right answer:** large regulated enterprises (banks, telcos, healthcare) with existing data-center investment and compliance mandates that require on-prem hardware; environments with extremely high, *predictable*, steady-state throughput where amortized hardware cost beats cloud LCU pricing; and any place where "one appliance, one vendor SLA, one compliance certificate" is itself the requirement, not just a technical one.

---

## 4. Software Load Balancers

A **software load balancer** is a program running on commodity hardware — a bare-metal server, a VM, or a container — that performs the same distribution/termination job in software.

**Representative products and what distinguishes each:**

| Software LB | Layer(s) | Distinguishing trait |
|---|---|---|
| **NGINX / NGINX Plus** | L4 & L7 | Event-driven, extremely low memory-per-connection; the default reverse proxy/LB for most web stacks; Plus adds active health checks, session persistence, dynamic reconfiguration via API |
| **HAProxy** | L4 & L7 | Purpose-built for load balancing (not a general web server first); best-in-class connection handling stats, widely regarded as the reference for TCP/HTTP proxying performance |
| **Envoy** | L4 & L7 | Built for the microservices era: first-class gRPC/HTTP2 support, dynamic configuration via the **xDS API** (no reload/restart to change routes), rich observability (stats, tracing) out of the box — the data-plane component underneath most [service meshes](../../07-microservices/service-mesh/README.md) (Istio, etc.) |
| **Traefik** | L7 | Auto-discovers backends from Docker/Kubernetes labels — config regenerates itself as containers come and go, aimed squarely at container-native environments |
| **LVS / IPVS** | L4 | Lives *inside the Linux kernel* — packets are forwarded by kernel code, not a userspace process, so it's extraordinarily fast; the low-level mechanism Kubernetes' `kube-proxy` itself is built on (in `ipvs` mode) |

**Why software LBs won the architectural argument for most modern systems:**
- **Elastic by construction** — it's just a process; run more instances behind a VIP or DNS name, or let an orchestrator (Kubernetes, an auto-scaling group) scale the fleet with demand.
- **Runs anywhere** — bare metal, VM, container, any cloud, laptop for local dev — no vendor-specific hardware dependency.
- **Config-as-code** — an NGINX or Envoy config is a text file (or, for Envoy, an API call); it lives in git, gets code-reviewed, and deploys through the same CI/CD pipeline as the application it fronts. This is a genuine architectural advantage over a proprietary hardware GUI.
- **Cheap and horizontally scalable** — commodity compute instead of specialized appliances; if one LB instance saturates, add another.

**The trade-offs, honestly stated:**
- **SSL/TLS termination consumes host CPU** — without dedicated crypto silicon, terminating a very large number of concurrent HTTPS connections is a real CPU cost (mitigated today by AES-NI CPU instructions, kernel TLS offload, and simply horizontal scaling of LB instances — the software answer to a hardware problem).
- **You own the operational burden** — patching, capacity planning for the LB tier itself, and its own high availability (see §9's Maglev discussion — "just run one NGINX box" reintroduces a single point of failure unless the LB layer is itself made redundant).

---

## 5. Hardware vs. Software LB — Direct Comparison

| Dimension | Hardware LB | Software LB |
|---|---|---|
| **Capex/Opex** | High upfront capital cost, licensing | Low — commodity compute, often pay-as-you-go |
| **Elasticity** | Fixed capacity per unit; scale = buy more boxes | Elastic — add instances/pods on demand |
| **Provisioning time** | Weeks–months (procurement, racking) | Seconds–minutes |
| **Raw throughput ceiling (single unit)** | Very high (custom ASICs, multi-Tbps) | High, but bounded by commodity CPU/NIC unless clustered |
| **Config management** | Often proprietary GUI/CLI (vendor-specific) | Config-as-code, git-versioned, API-driven |
| **Best fit** | Regulated enterprises, steady predictable massive throughput, on-prem compliance | Cloud-native, containerized, auto-scaling, CI/CD-driven architectures |
| **Failure domain** | Appliance pair (active-passive/active-active) — still a discrete unit | Horizontally distributed fleet — no single unit matters |
| **Representative products** | F5 BIG-IP, Citrix ADC, A10 Thunder | NGINX, HAProxy, Envoy, Traefik, LVS |

**The senior framing:** the hardware-vs-software choice is really a proxy for a bigger question — *does your organization's infrastructure model favor capex-heavy, vendor-managed, steady-state appliances, or opex-heavy, elastic, code-defined infrastructure?* Most net-new architectures answer "software" (often consumed as a managed cloud service — see §6) because elasticity and config-as-code compound in value as systems get more dynamic; hardware persists where compliance, existing investment, or truly extreme steady throughput dominate the calculus.

---

## 6. Cloud / Elastic Load Balancers — Managed LB-as-a-Service

A **cloud load balancer** ("elastic load balancer" in AWS's own naming) is a **fully managed service**: the cloud provider runs a fleet of load-balancing nodes (usually software LBs under the hood, running on the provider's own infrastructure) behind a stable DNS name or Anycast IP, auto-scales that fleet with your traffic, and bills you for usage instead of you owning any box. It combines software LB flexibility with hardware LB "someone else worries about the ops" — genuinely a third category, not just "software LB, but rented."

### AWS Elastic Load Balancing (ELB) family

| Type | OSI Layer | Protocols | Key trait |
|---|---|---|---|
| **Classic Load Balancer (CLB)** | L4 & L7 | HTTP/HTTPS/TCP | Legacy (2009-era); AWS explicitly steers new workloads away from it — no target groups, fewer features |
| **Application Load Balancer (ALB)** | L7 | HTTP/HTTPS, WebSocket, gRPC | Content-based routing (path/host/header/query-string rules), **target groups**, native Lambda targets, no static IP by default |
| **Network Load Balancer (NLB)** | L4 | TCP/UDP/TLS | Millions of requests/sec at sub-millisecond added latency, **static/Elastic IP support**, preserves client source IP natively, required when you need non-HTTP protocols or IP allow-listing |
| **Gateway Load Balancer (GLB)** | L3/L4 | IP | Not for your application traffic at all — a **transparent bump-in-the-wire** for third-party virtual appliances (firewalls, IDS/IPS, deep-packet-inspection boxes); combines L3 routing with L4 balancing so traffic can be transparently steered through an appliance fleet and back |

**How "elastic" is actually achieved:** each ELB is not one box — it's a fleet of nodes spread across Availability Zones, addressed by a DNS name whose resolved IPs change as the fleet scales; AWS scales that node fleet based on traffic (and can be pre-warmed via a support request ahead of a known massive spike, since even elastic scaling has a ramp-up curve); target groups decouple "what backends exist" from "what routing rules apply," so Auto Scaling Groups can register/deregister EC2 instances, containers, or IPs without touching the LB's routing config; **deregistration delay** (connection draining) ensures in-flight requests finish before a target is removed during scale-in or deploys.

**A common production pattern this enables:** NLB in front (for a stable static IP to put in DNS/allow-lists and to absorb raw volume) forwarding to an ALB behind it (for content-based routing) — the L4→L7 layering from §2, expressed as an actual AWS reference architecture.

### GCP Cloud Load Balancing family

- **Global external Application Load Balancer** — L7, **Anycast** single global IP (see §8), backed by Google's own global network/edge (Google Front End), so a client connects to the nearest Google point of presence over Google's private backbone rather than the public internet for most of the trip.
- **Regional external/internal Application Load Balancer** — L7, scoped to one region, for workloads that don't need (or shouldn't have) global reach.
- **Global/Regional external Network Load Balancer** — L4, proxy-based (global) or pass-through (regional).
- **Internal TCP/UDP Load Balancer** — L4, purely inside a VPC for service-to-service traffic, never touching the public internet.

GCP's global LB is architecturally interesting because it's the closest public expression of the internal Maglev design (§9) — a single Anycast IP fronting a globally distributed, consistently-hashed backend fleet, which is precisely the pattern Maglev's paper describes.

### Azure Load Balancing family

- **Azure Load Balancer** — L4, regional, for TCP/UDP.
- **Application Gateway** — L7, regional, with an integrated **Web Application Firewall (WAF)** option.
- **Front Door** — L7, **global**, Anycast-edge, CDN-adjacent (TLS termination and routing at Microsoft's edge points of presence, similar in spirit to GCP's global LB and AWS Global Accelerator).
- **Traffic Manager** — pure **DNS-based** global load balancing (§8) — no data-plane proxying at all, just intelligent DNS responses.

### Cross-cloud equivalence table (useful for translating between clouds in an interview or design doc)

| Need | AWS | GCP | Azure |
|---|---|---|---|
| L7 HTTP(S) regional | ALB | Regional External App LB | Application Gateway |
| L7 HTTP(S) global/Anycast | ALB + CloudFront/Global Accelerator | Global External App LB | Front Door |
| L4 TCP/UDP | NLB | Network LB | Azure Load Balancer |
| Internal-only L4 | NLB (internal scheme) | Internal TCP/UDP LB | Internal Load Balancer |
| DNS-only global routing | Route 53 (latency/geo routing) | Cloud DNS + routing policies | Traffic Manager |
| Third-party appliance chaining | Gateway Load Balancer | (via Network LB + appliance VMs) | (via NVA + UDR) |

**Why "cloud/elastic LB" deserves its own category in an interview answer, not just "software LB, in the cloud":** the value isn't merely "someone else's NGINX" — it's the **managed control plane**: automatic multi-AZ/multi-region node scaling, integrated health checks wired directly to Auto Scaling, managed TLS certificates (ACM, Google-managed certs) with automatic renewal, native DDoS protection (AWS Shield, Google Cloud Armor, Azure DDoS Protection) at the provider's network edge before traffic ever reaches your VPC, and usage-based billing with zero procurement lead time. That operational bundle is what "elastic" is actually paying for.

---

## 7. DNS-Based Load Balancing & Global Server Load Balancing (GSLB)

Everything above balances traffic *within* a data center or region. A different layer of load balancing decides **which data center or region a client talks to in the first place** — this happens before a single TCP packet reaches any of the load balancers discussed so far.

- **DNS round robin:** a domain resolves to multiple A/AAAA records; resolvers/clients rotate through them. Crude but simple — **no health awareness** (a dead IP stays in rotation until manually removed) and **TTL-driven staleness** (resolvers and intermediate caches hold onto an IP for the TTL duration, so failover isn't instant, and misbehaving resolvers sometimes ignore TTL entirely).
- **Latency-based / geolocation-based DNS routing:** the DNS provider returns different IPs depending on where the query originated — AWS Route 53's latency-based and geolocation routing policies, GCP Cloud DNS routing policies, and Azure Traffic Manager's performance routing are the managed versions. This is how a user in Singapore and a user in Frankfurt get routed to *different regional deployments* of the same service, each fronted by its own regional load balancer from §6.
- **Health-checked DNS failover:** the DNS provider actively probes each region's health endpoint and stops returning an IP for an unhealthy region — this is the mechanism behind most "automatic regional failover" architectures, and it inherits DNS's fundamental limitation: failover is only as fast as the **lowest TTL a client/resolver actually respects**, typically tens of seconds to minutes, not the sub-second failover an in-region load balancer gives you.
- **Anycast:** the same IP address is announced from multiple physical locations via BGP, and internet routing itself delivers a packet to the *topologically nearest* announcing location — no DNS trickery, no TTL delay, because the "choice" happens at the network-routing layer, not the naming layer. This is how Cloudflare, Google's global LB, AWS Global Accelerator, and Azure Front Door achieve both low-latency edge entry *and* near-instant failover (withdraw the BGP route from a failed site and traffic reroutes at internet-routing speed, far faster than any DNS TTL).

**Global Server Load Balancing (GSLB)** is the umbrella term for combining these: health-aware, latency/geo-aware routing across *entire regional deployments*, each of which has its own local load balancer (§2–§6) handling distribution *within* that region. Think of it as load balancing *between* load balancers. F5 and Citrix both sell dedicated GSLB appliances/modules (part of why "hardware LB vendor" and "GSLB vendor" are often the same company); in cloud-native architectures, Route 53 / Cloud DNS / Traffic Manager plus Anycast-edge services (CloudFront, Cloud CDN, Azure Front Door) fill the same role.

**The layered mental model worth stating explicitly in an interview:** *Anycast/DNS decides which region; the regional load balancer (ALB/NLB/NGINX/F5) decides which instance within that region; the algorithm (§9) decides how fairly.* Three distinct decisions, three distinct mechanisms, three distinct failure domains — collapsing them into "there's a load balancer" is exactly the shallow answer this guide exists to prevent.

---

## 8. Load Balancing Algorithms — Deep Dive

| Algorithm | How it works | Best for |
|---|---|---|
| **Round Robin** | Requests cycle through servers in fixed order (server 1, 2, 3, 1, 2, 3...) | Homogeneous servers, roughly equal request cost |
| **Weighted Round Robin** | Like round robin, but servers with more capacity get proportionally more requests (weight 3 gets 3 requests per 1 that weight-1 gets) | Heterogeneous server sizes (some 2-4x bigger) |
| **Least Connections** | Routes to the server currently handling the fewest active connections | Requests with variable/long processing time (some take 10ms, some take 10s) |
| **Weighted Least Connections** | Least connections, adjusted by declared server capacity/weight | Heterogeneous servers *and* variable request cost simultaneously |
| **Least Response Time** | Routes to the server with the lowest recent latency **and** fewest connections combined | Latency-sensitive workloads, heterogeneous backend performance |
| **IP Hash** | `hash(client_IP) mod N` picks a server — same client always hits the same server (until N changes) | Session affinity without a shared session store |
| **Consistent Hashing** | Servers and keys are mapped onto a hash ring (e.g., SHA-1 of server-ID); a request's key hashes to a ring position and is routed to the next server clockwise | Session affinity *and* cache-node selection where the pool resizes often — minimizes reshuffling on scale events (see below) |
| **Random / Power of Two Choices** | Pick 2 random servers, route to the less loaded of the two | Very large server pools where tracking exact state of all servers is too expensive; provably close to optimal load distribution with far less coordination overhead than full least-connections |
| **URL Hash** | `hash(request_URL)` picks a server | Maximizing cache hit rate when backends cache content locally — the same URL always lands on the same (and therefore already-warm) cache |

### Why consistent hashing matters more than the one-line summary suggests

With plain **modulo hashing** (`hash(key) mod N`), changing `N` (adding or removing one server) reshuffles the mapping for **almost every key** — a single server crash or scale-out event invalidates nearly the entire cache/session mapping at once, a cascading-failure risk.

**Consistent hashing** fixes this: both servers and keys are placed on a conceptual ring (using a hash function like SHA-1), and a key is routed to the *first server found going clockwise* from its position. Adding or removing one server only remaps the keys that fell between it and its neighbor on the ring — roughly **`1/N` of keys move**, not nearly all of them. Real implementations add **virtual nodes** (each physical server gets many points on the ring, not one) to smooth out uneven load distribution that a small number of real points would otherwise cause. This exact technique underlies Amazon's Dynamo, most distributed caches ([Caching](../caching/README.md)), CDN edge-node selection ([CDN](../cdn/README.md)), and — critically for this topic — Google's Maglev (§9).

### The interview-critical distinction

Round Robin assumes uniform request cost. The moment request processing times vary significantly (e.g., some API calls do a quick cache lookup, others do a heavy report generation), Round Robin can send a burst of "heavy" requests to one server while others sit idle — **Least Connections** is the correct answer in that scenario. At very large fleet sizes where tracking every server's live connection count adds coordination overhead, **Power of Two Choices** gets nearly the same balance quality with far less bookkeeping — worth naming as the "Google-scale" answer to the same problem.

---

## 9. Health Checks — the Availability Half of a Load Balancer's Job

A load balancer is only as good as its ability to detect and route around unhealthy nodes.

- **Active health checks:** the LB itself periodically probes each backend — a **TCP check** (can I open a connection?), an **HTTP check** (does `GET /health` return 200 within the timeout?), a **gRPC health-checking-protocol check** (the standard `grpc.health.v1.Health` service), or a **custom script/plugin check** for domain-specific readiness (e.g., "is this node's local cache warm?").
- **Passive health checks:** the LB observes real traffic — if a node starts returning 5xx errors or timing out on actual requests, it's marked unhealthy without a separate probe, avoiding the overhead of dedicated probe traffic.
- **Key tunables, and why each exists:** *check interval* (how often — trade responsiveness against probe overhead), *timeout* (how long to wait for a response), *unhealthy threshold* (consecutive failures before removal — avoids yanking a node for one transient blip), *healthy threshold* (consecutive successes before re-admission — avoids "flapping" a recovering node in and out of rotation, which can itself cause a thundering-herd of traffic slamming a barely-recovered server).
- **Readiness vs. liveness** (a distinction that carries directly into [Kubernetes](../../11-technologies/kubernetes/README.md)): a *liveness* check asks "is the process alive at all," a *readiness* check asks "can it currently serve traffic well" (e.g., still warming a cache, or intentionally draining before shutdown) — a load balancer should route only on readiness, not liveness alone, or it will send traffic to a technically-alive-but-not-ready node.

---

## 10. SSL/TLS Termination — Three Modes, Three Trade-offs

Where encryption is decrypted matters architecturally, not just operationally.

| Mode | What happens | Why you'd choose it |
|---|---|---|
| **SSL/TLS Termination (offload)** | LB decrypts client traffic, forwards **plaintext** to the backend over the internal network | Removes CPU-expensive crypto from every backend instance (or lets a hardware LB's crypto silicon absorb it, §3); backends are simpler; centralizes certificate management. Requires the internal network to be trusted (private VPC/subnet). |
| **SSL Passthrough** | LB forwards the encrypted bytes **untouched**; the backend itself terminates TLS | Needed when the backend must see the actual TLS handshake (client-certificate/mTLS authentication that must reach the app), or for regulatory "encryption must be end-to-end, full stop" requirements. Only possible at **L4** — an L7 LB can't route on HTTP content it can't decrypt, which is a real trade-off: passthrough gives up content-based routing. |
| **SSL Bridging / Re-encryption** | LB terminates the client's TLS connection, inspects/routes at L7, then opens a **new, separately-encrypted** TLS connection to the backend | The "defense in depth" middle ground: you get L7 content-based routing *and* encryption on both hops. This is the default posture in [service mesh](../../07-microservices/service-mesh/README.md) architectures doing mTLS between every service. Costs two full crypto operations per request instead of one. |

**A frequently-missed follow-up:** if terminating at the LB, the backend loses the client's real IP address unless the LB explicitly forwards it — hence the `X-Forwarded-For` header (L7) or NLB's native source-IP preservation (L4, §6) — a detail worth naming because "why does my app see the load balancer's IP instead of the user's" is a very real, very common bug.

---

## 11. Session Affinity ("Sticky Sessions") — and Why It's a Trap

If application state lives in a specific server's memory (see the anti-pattern in [Scalability](../../01-foundations/scalability/README.md)), the LB can be configured to always route a given client to the same backend using a cookie (L7) or IP hash (L4, §8).

**Why senior architects are wary of this:** sticky sessions reintroduce a form of statefulness that undermines the whole point of horizontal scaling — if that one server dies, that user's session is gone (unless there's a fallback), and load distribution becomes uneven (a server with many "sticky" long-lived users can't shed load even if it's overloaded). **The preferred fix is externalizing session state** (Redis, a database) so the LB can be a completely dumb, stateless router — sticky sessions are a legitimate but second-choice tool, reached for only when externalizing state genuinely isn't feasible (e.g., WebSocket connections, which are inherently pinned to one server for their lifetime — see [WebSockets](../../08-api-design/websockets/README.md)).

---

## 12. Real-World Example: How Google's Maglev Load Balancer Achieves Consistent Hashing at Massive Scale

Google's **Maglev** (described in their public 2016 NSDI paper) is a software network load balancer that handles a significant portion of Google's incoming traffic, sitting in front of everything from Search to YouTube — and it's a case study that ties together nearly every concept above.

- Instead of a traditional single "load balancer box" (a potential bottleneck and single point of failure), Maglev runs as **many identical software instances behind Equal-Cost Multi-Path (ECMP) routing at the network layer** — so there is no single load balancer at all, just a horizontally scaled fleet of them, each capable of handling any packet. This is the software-LB elasticity argument from §4, deployed at Google's own scale rather than bought as an appliance.
- Maglev uses **Direct Server Return** (§2) — it routes the inbound packet to a backend, but the backend replies directly to the client — which is why one Maglev instance can push far more throughput than its own NIC would suggest: it never has to relay the (typically larger) response.
- To keep TCP connections stable even as backend pools change size (servers added/removed), Maglev uses a **consistent hashing scheme** (§8) so that, as much as possible, the same connection keeps landing on the same backend even after the backend pool is resized — minimizing connection resets during scaling events or failures.
- This design lets Google scale the load-balancing layer itself horizontally and disposably, avoiding the classic "load balancer as single point of failure" trap — and it's the direct ancestor of GCP's public global load balancing product (§6).

**The lesson:** the load balancer itself must be designed with the same scalability/availability principles ([Scalability](../../01-foundations/scalability/README.md), [Availability & Reliability](../../01-foundations/availability-reliability/README.md)) as the services behind it — "just put an NGINX box in front" doesn't scale past a certain point without the LB layer itself becoming the bottleneck or single point of failure, which is exactly the problem cloud-managed LBs (§6) exist to solve for everyone who isn't Google.

---

## 13. Configuration, Concretely: NGINX and HAProxy

Seeing the algorithms and concepts as real config demystifies "load balancer" from an abstract box into a program with a text file.

**NGINX — weighted round robin with health-adjacent settings:**
```nginx
upstream pricing_backend {
    least_conn;                              # algorithm: least connections
    server 10.0.1.10:8080 weight=3;          # bigger instance gets 3x traffic
    server 10.0.1.11:8080 weight=1;
    server 10.0.1.12:8080 weight=1 backup;   # only used if the others are down
    keepalive 64;                             # reuse connections to backends
}

server {
    listen 443 ssl;
    ssl_certificate     /etc/nginx/certs/example.com.crt;
    ssl_certificate_key /etc/nginx/certs/example.com.key;

    location /api/ {
        proxy_pass http://pricing_backend;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;  # preserve real client IP
        proxy_set_header X-Real-IP $remote_addr;
        proxy_next_upstream error timeout http_502 http_503;          # retry a different backend on failure
    }
}
```

**HAProxy — explicit active health checks and consistent-hash session affinity:**
```
backend pricing_backend
    balance leastconn
    option httpchk GET /health
    http-check expect status 200
    default-server inter 5s fall 3 rise 2      # check every 5s, 3 fails to mark down, 2 successes to re-admit
    server app1 10.0.1.10:8080 check weight 100
    server app2 10.0.1.11:8080 check weight 100

backend cache_backend
    balance uri                                 # URL hash — same URL, same cache node
    hash-type consistent
    server cache1 10.0.2.10:11211 check
    server cache2 10.0.2.11:11211 check
```

---

## 14. Spring Boot Example: Client-Side Load Balancing with Spring Cloud LoadBalancer

While infrastructure-level load balancers (NGINX, ALB, Envoy) handle most cases, senior architects should also know **client-side load balancing** — where a calling service itself picks which instance of a downstream service to call, common in service-mesh-free microservice setups.

```java
// build.gradle: org.springframework.cloud:spring-cloud-starter-loadbalancer

@Configuration
public class LoadBalancerConfig {

    // Custom load-balancing strategy: swap the default round-robin for a
    // least-response-time-style strategy when downstream call costs are non-uniform.
    @Bean
    public ServiceInstanceListSupplier discoveryClientServiceInstanceListSupplier(
            ConfigurableApplicationContext context) {
        return ServiceInstanceListSupplier.builder()
            .withDiscoveryClient()          // pulls live instance list from Eureka/Consul
            .withHealthChecks()              // filters out instances failing health checks
            .withCaching()                   // avoids hammering the discovery service
            .build(context);
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class PricingClient {

    private final RestClient.Builder restClientBuilder;

    // "http://pricing-service" is a LOGICAL name resolved by the load balancer
    // to one of N live instances -- the calling code never hardcodes an IP or port.
    @LoadBalanced
    RestClient pricingRestClient() {
        return restClientBuilder.baseUrl("http://pricing-service").build();
    }

    public Price getPrice(String sku) {
        return pricingRestClient()
            .get()
            .uri("/price/{sku}", sku)
            .retrieve()
            .body(Price.class);
        // Under the hood: Spring Cloud LoadBalancer picks a healthy pricing-service
        // instance from the service registry, applying round-robin (default) or a
        // custom strategy, and retries on a different instance on connection failure.
    }
}
```

```yaml
# application.yml
spring:
  cloud:
    loadbalancer:
      health-check:
        interval: 5s          # active health check interval -- matches infra-LB concepts
        path:
          default: /actuator/health
      retry:
        enabled: true         # retry on a DIFFERENT instance if one call fails
```

**Why this matters at senior level:** it shows you understand load balancing isn't confined to a physical box at the network edge — the same algorithmic concepts (round robin, least connections, health checks) recur at the application layer in service-to-service calls, and knowing when to reach for infra-level vs client-side load balancing (client-side avoids an extra network hop, but couples the algorithm choice into every service) is itself a design decision.

---

## 15. Decision Framework: Which Kind of Load Balancer, When

| Scenario | Recommendation | Why |
|---|---|---|
| Cloud-native app, auto-scaling backends, HTTP/HTTPS | **Cloud L7 LB** (ALB / GCP App LB / App Gateway) | Managed health checks tied to auto-scaling, managed certs, zero procurement |
| Non-HTTP protocol, extreme throughput, need static IP | **Cloud L4 LB** (NLB / Network LB / Azure LB) | Only cloud option offering static IP + line-rate TCP/UDP without managing hardware |
| Multi-region, need nearest-datacenter routing with fast failover | **Anycast/GSLB** (CloudFront/Global Accelerator, Cloud CDN, Front Door) + regional LBs underneath | DNS alone is too slow to fail over; Anycast reroutes at BGP speed |
| On-prem, regulated industry, existing hardware investment, steady massive throughput | **Hardware LB** (F5, Citrix ADC) | Compliance certs, single-vendor support, amortized cost at steady scale |
| Kubernetes cluster, need config-as-code, service mesh | **Software LB** (Envoy, NGINX Ingress, HAProxy) | Runs as a pod, scales with the cluster, integrates with the mesh control plane |
| Third-party security appliance (firewall/IDS) needs to inspect all traffic transparently | **Gateway Load Balancer** pattern | Purpose-built for bump-in-the-wire appliance chaining |
| Service-to-service calls inside a microservice fleet you own end-to-end | **Client-side LB** (Spring Cloud LoadBalancer, gRPC client-side LB) | Saves a network hop; couples algorithm choice to the caller, which is fine when you control both ends |

---

## 16. Common Pitfalls

- Defaulting to sticky sessions instead of externalizing state — treats a symptom (server needs client affinity) instead of the cause (state shouldn't live in server memory).
- Ignoring L4 vs L7 trade-offs and assuming "load balancer" always means an HTTP-aware one — costs unnecessary latency/complexity for non-HTTP workloads, or silently loses routing capability if forced into passthrough mode.
- **Treating "hardware," "software," and "cloud/elastic" as interchangeable** — they differ in elasticity, cost model, and operational ownership, and an interviewer asking "how would this scale" expects you to know which category you're even proposing.
- Forgetting that the load balancer itself needs to be highly available (DNS round-robin across multiple LB IPs, Anycast, or a managed/redundant solution) — a single load balancer instance or appliance is just a relocated single point of failure.
- Using Round Robin for workloads with highly variable per-request cost, causing uneven load despite "balanced" request counts.
- Choosing SSL passthrough and then expecting content-based (L7) routing — the two are mutually exclusive at the same hop.
- Assuming DNS-based failover is instant — TTL and resolver caching mean it's typically tens of seconds to minutes, not sub-second like an in-region LB health check removal.
- Using modulo-based hashing for a resizable pool of cache/session backends instead of consistent hashing — causes near-total cache invalidation or session loss on every scale event.

---

## 17. 60-Second Interview Answer

> "A load balancer is fundamentally a reverse proxy that distributes traffic and removes unhealthy nodes via health checks. It operates at L4 — IP/port only, protocol-agnostic, extremely fast — or L7, which terminates and inspects HTTP so it can route on path or headers, at some latency cost. Beyond that layer choice, I think in three deployment categories: hardware appliances like F5 — high raw throughput and bundled enterprise features, but capex-heavy and not elastic; software LBs like NGINX, HAProxy, or Envoy — elastic, config-as-code, cheap, and the default for cloud-native systems; and managed cloud LBs like AWS's ALB/NLB or GCP's global load balancer, which give you that same software flexibility plus a fully managed, auto-scaling control plane with integrated health checks and certificate management. Above the single-region LB sits a separate layer — DNS-based or Anycast-based global load balancing — which decides which *region* a client even reaches before any of that. For algorithm choice inside a region, I'd default to round robin for uniform-cost requests but switch to least-connections when request cost varies, and reach for consistent hashing specifically when the backend pool resizes often, since naive modulo hashing reshuffles almost every key on every scale event. I try to avoid sticky sessions by externalizing session state to Redis, since sticky sessions reintroduce statefulness that undermines horizontal scalability — I'd only accept them for genuinely stateful protocols like long-lived WebSocket connections, and I'd terminate TLS at the LB unless end-to-end encryption is a hard requirement, in which case I'd re-encrypt to the backend rather than pass through, so I don't lose L7 routing."

**Related:** [Networking Fundamentals — OSI, TCP, UDP](../../08-api-design/networking-fundamentals/README.md) · [Scalability](../../01-foundations/scalability/README.md) · [Rate Limiting](../rate-limiting/README.md) · [API Gateway](../api-gateway/README.md) · [Availability & Reliability](../../01-foundations/availability-reliability/README.md) · [Caching](../caching/README.md) · [CDN](../cdn/README.md) · [Service Mesh](../../07-microservices/service-mesh/README.md)
