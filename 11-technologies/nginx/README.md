# Nginx — Reverse Proxy, Load Balancer, Static Server

> **Mental model:** Nginx won the web because of its **event-driven, non-blocking architecture**: a handful of single-threaded worker processes, each multiplexing tens of thousands of connections via epoll/kqueue — versus the older Apache model of a thread/process per connection. That architecture is *why* one small VM running Nginx can front thousands of app servers, and it's the same event-loop idea underneath Node.js, Redis, and Envoy — a genuinely reusable systems insight.

---

## 1. The three jobs Nginx does in almost every stack

- **Reverse proxy:** terminates client connections (and TLS), forwards to app servers (`proxy_pass`). What you gain: TLS offload, slow-client absorption (Nginx buffers the slow mobile client's bytes; your [Tomcat](../../12-app-servers/tomcat/README.md) thread is freed in milliseconds instead of being held for seconds — this alone multiplies app-server capacity), compression, connection reuse upstream, and a uniform place for headers (`X-Forwarded-For`, `X-Request-ID` for [tracing](../../10-security-observability/observability/README.md)).
- **Load balancer:** `upstream` blocks with round-robin (default), `least_conn`, `ip_hash` (session affinity), weights, plus passive health checks (`max_fails`/`fail_timeout`) — the [L7 load balancer](../../02-building-blocks/load-balancers/README.md) concept in config form.
- **Static file server + cache:** serves files with `sendfile` (zero-copy — the same syscall [Kafka](../kafka/README.md) uses), and `proxy_cache` turns it into a mini-[CDN](../../02-building-blocks/cdn/README.md) edge.

## 2. The config that covers 90% of production use

```nginx
worker_processes auto;                    # one worker per core
events { worker_connections 10240; }      # per worker — event loop, not threads!

http {
  upstream app {                          # the load-balanced backend pool
    least_conn;
    server 10.0.0.11:8080 weight=2 max_fails=3 fail_timeout=10s;
    server 10.0.0.12:8080;
    keepalive 32;                         # reuse upstream connections
  }

  proxy_cache_path /var/cache/nginx keys_zone=api_cache:10m max_size=1g;

  server {
    listen 443 ssl http2;
    ssl_certificate     /etc/ssl/fullchain.pem;
    ssl_certificate_key /etc/ssl/privkey.pem;

    location /static/ { root /var/www; expires 30d; }   # static + cache headers

    location /api/ {
      proxy_pass http://app;
      proxy_set_header Host $host;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Request-ID $request_id;        # trace correlation
      proxy_read_timeout 5s;                            # timeouts are resilience!
      proxy_next_upstream error timeout http_502;       # retry next backend
      proxy_cache api_cache;                            # micro-caching GETs
      proxy_cache_valid 200 10s;
      limit_req zone=perip burst=20 nodelay;            # rate limiting
    }
  }
  limit_req_zone $binary_remote_addr zone=perip:10m rate=10r/s;
}
```

Every directive above maps to a vault concept: `proxy_read_timeout` → [timeouts](../../07-microservices/resilience-patterns/README.md), `limit_req` (leaky-bucket) → [rate limiting](../../02-building-blocks/rate-limiting/README.md), `proxy_cache_valid 200 10s` ("micro-caching") → [caching](../../02-building-blocks/caching/README.md) — 10 seconds of cache on a hot read endpoint can absorb enormous traffic while staying nearly fresh.

## 3. Installation & operations

```bash
# Ubuntu                                # Docker
sudo apt install nginx                  docker run -d -p 80:80 \
sudo systemctl enable --now nginx         -v $PWD/nginx.conf:/etc/nginx/nginx.conf:ro nginx

nginx -t                # ALWAYS: validate config before applying
sudo nginx -s reload    # graceful reload: new workers start, old ones drain
                        # — zero-downtime config change (deployment-pattern thinking!)
tail -f /var/log/nginx/access.log error.log
```

`nginx -s reload` is worth understanding mechanically: the master process spawns new workers with the new config while old workers finish their in-flight connections — connection draining, [as in every rolling deploy](../../07-microservices/deployment-patterns/README.md).

## 4. The from-scratch implementation

[`MiniNginx.java`](MiniNginx.java) implements the essence in one file: **an event-loop reverse proxy on Java NIO** (one thread, `Selector`-multiplexed non-blocking sockets — the actual Nginx architecture, not a thread-per-connection fake), with **round-robin + least-connections upstream selection, passive health marking on connect failure, and `X-Forwarded-For` injection**. Reading it teaches you what "10k connections on one thread" physically means.

## 5. Interview soundbites

- "Nginx is an event loop: a few workers, epoll-multiplexed, so idle connections cost a socket, not a thread — that's why it fronts thousands of servers from one box."
- "The reverse proxy absorbs slow clients — the app-server thread is released in milliseconds while Nginx trickles the response out; this alone multiplies backend capacity."
- "`nginx -s reload` is connection-draining zero-downtime config deployment — old workers finish, new workers take over."
- "Micro-caching — `proxy_cache_valid 200 10s` — is the cheapest scalability trick in the book for read-heavy endpoints."

**Related:** [Load Balancers](../../02-building-blocks/load-balancers/README.md) · [CDN](../../02-building-blocks/cdn/README.md) · [Kong](../kong-api-gateway/README.md) · [Tomcat](../../12-app-servers/tomcat/README.md)
