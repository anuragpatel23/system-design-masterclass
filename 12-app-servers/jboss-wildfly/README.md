# JBoss / WildFly — The Full Application Server

> **Mental model:** WildFly (community successor of JBoss AS; **JBoss EAP** is Red Hat's supported build of the same code) is a full **Jakarta EE** application server: everything Tomcat does, plus managed subsystems for JMS messaging, distributed transactions (JTA), EJBs, connection pooling, clustering, and security — configured centrally and administered through a real **management CLI**. Enterprise estates (banks, insurers, telcos) run large WildFly/EAP fleets, and interviews there ask about it concretely.

---

## 1. Architecture: subsystems + profiles

WildFly is a small modular core loading **subsystems** (undertow = web, messaging-activemq = JMS, datasources, infinispan = caching/sessions, ejb3, security…), declared in **one config file**: `standalone/configuration/standalone.xml`. The app declares *what it needs* (`@Resource DataSource`, `@Inject JMSContext`); the server provides *how* — pool sizes, brokers, transaction managers are ops-owned config, not app code. That inversion (container-managed resources) is the EE model in one sentence — and its echo today is the [service mesh](../../07-microservices/service-mesh/README.md)/platform providing cross-cutting concerns outside app code.

## 2. Installation & the two operating modes

```bash
wget https://github.com/wildfly/wildfly/releases/download/34.0.0.Final/wildfly-34.0.0.Final.tar.gz
tar xzf wildfly-*.tar.gz && cd wildfly-*
./bin/add-user.sh                          # create a management user (console/CLI auth)
./bin/standalone.sh                        # mode 1: STANDALONE — one server, one config
# http://localhost:8080 (apps) · http://localhost:9990 (management console)
```

- **Standalone mode:** one JVM, config in `standalone.xml` (variants: `standalone-full.xml` adds messaging; `-ha` adds clustering). This is what containers/K8s deployments use — the orchestrator does the fleet management.
- **Domain mode (the pre-Kubernetes fleet manager — know the vocabulary):** a **domain controller** holds config centrally in `domain.xml`; **host controllers** on each machine manage local **server instances**, grouped into **server groups** that share config + deployments. Deploy to a server group ⇒ every member gets the WAR — consistent config across 40 servers, solved in 2012. The honest framing: domain mode and [Kubernetes](../../11-technologies/kubernetes/README.md) solve the same problem (declared desired state for a fleet); K8s won the generic case, domain mode persists in EE estates.

## 3. How deployment works

1. **Deployment scanner** (standalone only): drop `app.war` into `standalone/deployments/`. Unlike Tomcat, it uses **marker files** — the scanner writes `app.war.deployed` on success, `app.war.failed` (with the error) on failure; touching `app.war.dodeploy` forces redeploy. Auditable file-based deployment state — a genuinely nice mechanism to describe.
2. **Management CLI (the production path):**

```bash
./bin/jboss-cli.sh --connect
[standalone@localhost:9990 /] deploy /path/app.war
[standalone@localhost:9990 /] deployment-info
[standalone@localhost:9990 /] undeploy app.war
# domain mode: deploy app.war --server-groups=production-group   ← fleet deploy, one command
# everything is scriptable — CI/CD calls the CLI (or its HTTP management API)
```

3. **Management console** (`:9990`) — the GUI over the same management model.

The CLI is a full management API, not just deployment: `/subsystem=datasources/data-source=MyDS:read-resource`, runtime metrics (`:read-attribute(name=statistics)`), config changes without editing XML — and in domain mode it's the fleet-wide control plane.

## 4. What the EE subsystems give you (and their system-design echoes)

- **Datasources:** container-managed JDBC pools with health validation — the app JNDI-looks-up `java:/jdbc/MyDS`; credentials and pool sizing are ops config.
- **Messaging (embedded ActiveMQ Artemis):** JMS queues/topics in-process — [message-queue semantics](../../02-building-blocks/message-queues/README.md) (acks, redelivery, DLQ) without running a separate broker; MDBs consume with container-managed concurrency.
- **JTA distributed transactions:** the container coordinates [2PC](../../05-distributed-systems/distributed-transactions/README.md) across a DB + JMS send — "consume, write, publish atomically" as an annotation. This is 2PC's natural habitat (single JVM, XA resources); across *services* you still use [sagas](../../05-distributed-systems/saga-pattern/README.md).
- **Clustering (`standalone-ha.xml`):** Infinispan-replicated HTTP sessions + JGroups membership — session survives instance death behind the [LB](../../02-building-blocks/load-balancers/README.md). The modern alternative remains [externalizing sessions](../../11-technologies/redis/README.md); replicated sessions trade write-fan-out cost for transparent failover.

## 5. Day-2 operations

```bash
tail -f standalone/log/server.log                      # the main log
./bin/jboss-cli.sh --connect --command="deployment-info"
./bin/jboss-cli.sh --connect \
  --command="/subsystem=undertow:read-attribute(name=statistics-enabled)"   # web pool stats
# undertow's worker pool = Tomcat's maxThreads equivalent (io-threads/task-max-threads)
# same exhaustion math, same jstack diagnosis, same downstream-timeout defense
```

JVM tuning in `bin/standalone.conf`; management interfaces bound to localhost or an admin network only; one server per container in modern estates, with standalone.xml templated by env vars.

## 6. Interview soundbites

- "WildFly is Tomcat plus managed subsystems — datasources, JMS, JTA, clustering — configured in standalone.xml and administered by a scriptable CLI; apps declare resources, the container provides them."
- "Deployment is marker-file scanning in dev and the management CLI in production — in domain mode one CLI command deploys to a whole server group."
- "Domain mode is pre-Kubernetes fleet management: central desired config pushed to host controllers — same problem K8s solves, EE-specific solution."
- "JTA gives real 2PC across XA resources inside one JVM — the legitimate 2PC use case; across services it's still sagas."

**Related:** [Tomcat](../tomcat/README.md) · [Jetty](../jetty/README.md) · [Distributed Transactions](../../05-distributed-systems/distributed-transactions/README.md) · [Message Queues](../../02-building-blocks/message-queues/README.md) · [Kubernetes](../../11-technologies/kubernetes/README.md)
