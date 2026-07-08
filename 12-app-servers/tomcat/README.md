# Apache Tomcat — The Default Servlet Container

> **Mental model:** Tomcat implements the Servlet/JSP specs and nothing more — HTTP in, servlet code out. That minimalism is why it won: it's the runtime under classic WAR deployments *and* under nearly every Spring Boot app (embedded). Operating Tomcat = understanding four things: **the directory layout, the deployment flow, server.xml (connectors + thread pools), and the JVM it runs in.**

---

## 1. Installation & layout

```bash
# Ubuntu package (runs as a systemd service)          # Or: raw tarball (any OS)
sudo apt install tomcat10                              wget https://dlcdn.apache.org/tomcat/tomcat-10/v10.1.x/bin/apache-tomcat-10.1.x.tar.gz
sudo systemctl enable --now tomcat10                   tar xzf apache-tomcat-*.tar.gz && cd apache-tomcat-*
                                                       ./bin/startup.sh      # catalina.sh run = foreground
# Verify: curl http://localhost:8080
```

```
CATALINA_HOME/
├── bin/        startup.sh, shutdown.sh, catalina.sh, setenv.sh (JVM opts live HERE)
├── conf/       server.xml (THE config), context.xml, tomcat-users.xml, logging.properties
├── webapps/    ← the deployment directory: drop WARs here
├── logs/       catalina.out (stdout), localhost_access_log (per-request)
├── work/       compiled JSPs, temp state
└── lib/        container-shared JARs (JDBC drivers go here for container-managed pools)
```

## 2. How deployment works (all four ways)

1. **Drop the WAR in `webapps/`** — the auto-deployer notices `myapp.war`, explodes it to `webapps/myapp/`, and serves it at `/myapp` (`ROOT.war` → `/`). Redeploy = replace the WAR; Tomcat undeploys the old context and starts the new. **The catch to know:** repeated hot-redeploys leak — old classloaders survive (threads/statics keep references), eventually giving `OutOfMemoryError: Metaspace`; production practice is **restart per deploy**, not hot-redeploy.
2. **Manager app** (`/manager/html` or its HTTP API — `curl --upload-file app.war "http://user:pw@host:8080/manager/text/deploy?path=/myapp"`) — remote deploys from CI; lock it down or remove it in production ([attack surface](../../10-security-observability/security-essentials/README.md)).
3. **Context descriptor** (`conf/Catalina/localhost/myapp.xml` pointing `docBase` at the app) — decouples app location from `webapps/`.
4. **Embedded (the modern default):** Spring Boot packages Tomcat *inside* the JAR — `java -jar app.jar` *is* the Tomcat deployment; all tuning moves to `application.yml` (`server.tomcat.threads.max` etc.). Same engine, inverted ownership — and why "we deploy WARs to a shared Tomcat" vs "each service ships its own embedded Tomcat in a [container](../../11-technologies/docker/README.md)" is an architecture-era marker.

**Zero-downtime:** Tomcat's **parallel deployment** (versioned WARs: `myapp##002.war` — new sessions to v2, old sessions finish on v1) exists and is worth naming; but the standard production answer is instance-level: [rolling restart behind the LB with connection draining](../../07-microservices/deployment-patterns/README.md).

## 3. server.xml — the 20% that matters

```xml
<Server port="8005" shutdown="SHUTDOWN">          <!-- local shutdown port -->
  <Service name="Catalina">
    <!-- CONNECTOR: where HTTP enters. THE tuning surface: -->
    <Connector port="8080" protocol="HTTP/1.1"
               maxThreads="200"          <!-- worker pool: max concurrent requests -->
               acceptCount="100"         <!-- backlog queue when all threads busy -->
               connectionTimeout="20000"
               compression="on" />
    <!-- maxThreads exhausted + acceptCount full => connections REFUSED.
         Slow downstream (DB) => threads park => pool exhausts => 'Tomcat is down'
         (it isn't — it's queuing). This is resilience-patterns.md happening locally. -->
    <Engine name="Catalina" defaultHost="localhost">
      <Host name="localhost" appBase="webapps" unpackWARs="true" autoDeploy="true">
        <Valve className="org.apache.catalina.valves.AccessLogValve"
               pattern="%h %t &quot;%r&quot; %s %b %D"/>   <!-- %D = ms — latency per request -->
      </Host>
    </Engine>
  </Service>
</Server>
```

- **Thread-pool sizing is the interview question:** 200 threads × 50ms/request ≈ 4,000 req/s ceiling; but 200 threads × 2s (slow DB) ≈ 100 req/s and a full backlog — the [capacity math](../../09-interview-prep/capacity-estimation.md) and the reason [timeouts on downstream calls](../../07-microservices/resilience-patterns/README.md) protect *Tomcat's own* pool. NIO connectors keep *idle* connections cheap, but each in-flight *request* still occupies a worker thread (until virtual threads change the game — worth one sentence as current knowledge).
- **JVM settings** go in `bin/setenv.sh`: `-Xms/-Xmx` (equal, ~50-75% of RAM), GC choice, `-XX:+HeapDumpOnOutOfMemoryError`. Tomcat performance issues are usually JVM/GC or downstream issues wearing a Tomcat costume.

## 4. Day-2 operations

```bash
tail -f logs/catalina.out                        # app stdout + container events
tail -f logs/localhost_access_log.*.txt          # per-request: status + %D latency
jstack <pid> | grep -A2 "http-nio-8080-exec"     # what are the worker threads doing?
                                                 # (all parked on a DB call = your answer)
ps aux | grep java; jstat -gc <pid> 1s           # GC health
curl -s localhost:8080/manager/status?XML=true   # pool usage, if manager enabled
```

Standard production posture: [Nginx](../../11-technologies/nginx/README.md) in front (TLS, static, slow clients), one app per Tomcat per [container](../../11-technologies/docker/README.md), logs to stdout shipped centrally, `/health` endpoint for [readiness](../../11-technologies/kubernetes/README.md), sessions externalized to [Redis](../../11-technologies/redis/README.md) so instances stay stateless.

## 5. Interview soundbites

- "Tomcat is just the servlet spec: connector accepts, a worker pool executes — `maxThreads` is the concurrency ceiling and thread-pool exhaustion from a slow downstream is the classic 'Tomcat outage' that isn't."
- "WAR-drop auto-deploy is fine for dev; production is immutable — bake the WAR (or embedded JAR) into an image and roll instances, because hot-redeploy leaks classloaders."
- "Spring Boot didn't remove Tomcat, it embedded it — same connector and pool, tuned from application.yml, one app per JVM."
- "Access-log `%D` plus a worker-thread jstack answers most 'Tomcat is slow' incidents in minutes."

**Related:** [JBoss/WildFly](../jboss-wildfly/README.md) · [Jetty](../jetty/README.md) · [Nginx](../../11-technologies/nginx/README.md) · [Resilience Patterns](../../07-microservices/resilience-patterns/README.md) · [Deployment Patterns](../../07-microservices/deployment-patterns/README.md)
