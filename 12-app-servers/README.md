# 12 — Application Servers: Tomcat, JBoss/WildFly, Jetty

> How Java applications actually reach users: the servlet containers and application servers that host them, how **deployment** works on each, and how to operate them day to day. This section matters in two interview contexts: enterprise-heavy shops (banks, insurers, telcos) still run substantial JBoss/WebLogic estates and ask about them directly; and everywhere else, "Spring Boot with embedded Tomcat" is *the* deployment unit — so knowing what Tomcat is doing under your Spring Boot app is knowing your own runtime.

## Contents

| Server | Note | What it represents |
|---|---|---|
| [Tomcat](tomcat/README.md) | The default servlet container | WAR deployment, server.xml, connectors & thread pools, the embedded-in-Spring-Boot reality |
| [JBoss / WildFly](jboss-wildfly/README.md) | The full Jakarta EE application server | Subsystems, standalone vs domain mode, the management CLI, EE features (JMS, EJB, distributed sessions) |
| [Jetty](jetty/README.md) | The embeddable, programmatic server | Server-as-a-library, the architecture inside API gateways and big-data UIs, Tomcat comparison |

## The one-paragraph history that frames everything

Java web apps began as **WARs deployed *into* a long-running server** (Tomcat, JBoss, WebLogic, WebSphere) — the server was infrastructure owned by ops, apps were tenants, and multiple apps shared one JVM. The **servlet container** (Tomcat, Jetty) implements the Servlet spec: HTTP handling, servlet lifecycle, sessions; a **full application server** (JBoss/WildFly, WebLogic) adds the rest of Jakarta EE — EJB, JMS, JTA distributed transactions, clustering. The cloud era **inverted the model**: Spring Boot embeds the server *inside* the app ("fat JAR"), making the deployment unit a self-contained process that fits [containers](../11-technologies/docker/README.md) and [Kubernetes](../11-technologies/kubernetes/README.md). Understanding both models — and why the inversion happened (one deployable, one owner, immutable [deployments](../07-microservices/deployment-patterns/README.md), no shared-JVM blast radius) — is the through-line of this section.

## Shared concepts you'll meet in all three notes

- **The servlet lifecycle & thread model:** a listener accepts connections; a **worker thread pool** executes servlet code per request; pool exhaustion = the [cascading-failure mechanics](../07-microservices/resilience-patterns/README.md) — sizing this pool is the #1 tuning knob on every server here.
- **WAR anatomy & context roots:** `WEB-INF/classes`, `WEB-INF/lib`, `web.xml`/annotations; `myapp.war` → `/myapp` unless configured otherwise.
- **Sessions & clustering:** sticky sessions vs replicated sessions vs the modern answer — [externalize state to Redis](../11-technologies/redis/README.md) and keep servers stateless ([scalability](../01-foundations/scalability/README.md)).
- **Fronting proxy:** all three normally sit behind [Nginx](../11-technologies/nginx/README.md)/httpd for TLS, static assets, and slow-client absorption.
- **Zero-downtime deployment:** rolling restarts behind the [LB](../02-building-blocks/load-balancers/README.md) with connection draining — the [deployment patterns](../07-microservices/deployment-patterns/README.md) applied to app servers.

Previous: [11 — Technology Deep Dives](../11-technologies/README.md) · Back to: [Master Index](../README.md)
