# Jetty — The Embeddable, Programmatic Server

> **Mental model:** Jetty made "the server is a library" first-class a decade before Spring Boot mainstreamed it: instead of deploying your app *into* a server, your `main()` **instantiates the server as objects** — `new Server(8080)`, add handlers, `server.start()`. That's why Jetty lives *inside* so much infrastructure you already use: Google App Engine's original runtime, Hadoop/Spark web UIs, many API gateways and proxies, and as Spring Boot's drop-in Tomcat alternative. Jetty completes the section by representing the model Tomcat moved *toward* and JBoss moved *away from*.

---

## 1. Embedded-first, in code

```java
// The entire server — this IS the deployment descriptor:
Server server = new Server(8080);

ServletContextHandler ctx = new ServletContextHandler("/");
ctx.addServlet(new ServletHolder(new MyApiServlet()), "/api/*");
server.setHandler(ctx);

server.start();       // no CATALINA_HOME, no webapps/, no server.xml
server.join();
```

- **The handler chain is the architecture:** everything in Jetty is a `Handler` (contexts, servlets, gzip, stats, rewrite) composed in code — the same middleware-pipeline idea as [Kong's plugin phases](../../11-technologies/kong-api-gateway/README.md) or Nginx modules, but as plain Java objects you can unit-test.
- **What embedding buys** (state these as the *model's* benefits, they're why the industry moved): the app is **one self-contained artifact** (`java -jar` — perfect for [containers](../../11-technologies/docker/README.md)); server config is code — versioned, reviewed, testable; integration tests start the *real* server on a random port in-process; no shared-JVM neighbors, no classloader-leak redeploys — [immutable deployments](../../07-microservices/deployment-patterns/README.md) by construction.
- **Spring Boot:** swap in with one dependency change (`spring-boot-starter-jetty` instead of `-tomcat`); tune via `server.jetty.*`. Functionally interchangeable with Tomcat for typical REST workloads — choose on organizational familiarity, not benchmarks folklore.

## 2. Standalone mode (it exists, and it's tidy)

```bash
wget https://repo1.maven.org/maven2/org/eclipse/jetty/jetty-home/12.x/jetty-home-12.x.tar.gz
tar xzf jetty-home-*.tar.gz
mkdir my-jetty-base && cd my-jetty-base            # JETTY_HOME (immutable install)
java -jar $JETTY_HOME/start.jar --add-modules=http,ee10-deploy   # vs JETTY_BASE (your config)
cp ~/myapp.war webapps/                             # hot-deploy scanner, like Tomcat
java -jar $JETTY_HOME/start.jar
```

The **JETTY_HOME / JETTY_BASE split** (immutable distribution vs your instance's config+apps) is a genuinely clean ops pattern — upgrades replace HOME, your BASE is untouched; config is composed from **modules** (`--add-modules=http,https,gzip`) rather than one monolithic XML.

## 3. Under the hood (why infrastructure embeds it)

- Same fundamental model as [Tomcat's connector/pool](../tomcat/README.md): NIO selectors accept and multiplex connections; a worker pool (`QueuedThreadPool`, default max ~200) runs requests — same exhaustion math, same [downstream-timeout defense](../../07-microservices/resilience-patterns/README.md).
- Jetty's reputation for proxy/gateway workloads comes from mature **async I/O throughout** (async servlets, streaming without buffering whole bodies) and a first-class **HTTP client** — an app can be an efficient intermediary (receive → transform → forward) without thread-per-hop. That's why gateways and big-data UIs ship it embedded.
- Jetty 12 restructured around protocol-agnostic core handlers with EE-versioned servlet layers (`ee10`/`ee11`) — one sentence of currency worth having.

## 4. Choosing between the three (the section's closing table)

| Question | Tomcat | JBoss/WildFly | Jetty |
|---|---|---|---|
| What is it? | Servlet container | Full Jakarta EE server | Servlet container as a library |
| Deployment unit | WAR into `webapps/` (or embedded via Spring Boot) | WAR/EAR via CLI/scanner/domain | Your JAR embeds it (standalone exists) |
| Config | server.xml | standalone.xml + management CLI | Code (or modules in JETTY_BASE) |
| Needs JMS/JTA/EJB, managed resources, enterprise estate | — | **Yes** | — |
| Default Spring Boot / general REST service | **Yes (default)** | overkill | Yes (swap-in) |
| Building a proxy/gateway/tool with an HTTP server inside | — | — | **Yes** |
| Fleet management story | External (K8s) | Domain mode (or K8s) | External (K8s) |

The honest summary sentence: **for a standard containerized REST service the Tomcat-vs-Jetty choice is a coin flip; the real fork is container vs full EE server, and that's decided by whether you need the EE subsystems or are living in an estate that already has them.**

## 5. Interview soundbites

- "Jetty inverted the deployment model first: the server is a library your main() composes — which is exactly the shape containers and Kubernetes want, and the shape Spring Boot made universal."
- "Everything is a Handler in a chain — middleware-pipeline architecture you can unit-test."
- "JETTY_HOME vs JETTY_BASE separates the immutable install from instance config — a clean upgrade story."
- "Same connector-plus-worker-pool physics as Tomcat; its async I/O maturity is why gateways embed it."

**Related:** [Tomcat](../tomcat/README.md) · [JBoss/WildFly](../jboss-wildfly/README.md) · [Kong](../../11-technologies/kong-api-gateway/README.md) · [Docker](../../11-technologies/docker/README.md) · [Deployment Patterns](../../07-microservices/deployment-patterns/README.md)
