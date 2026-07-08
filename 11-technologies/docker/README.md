# Docker — Containers, Images, Layers

> **Mental model:** a container is **not a lightweight VM** — it's an ordinary Linux **process** wearing isolation: **namespaces** give it a private view (its own PIDs, network, filesystem mounts, hostname), **cgroups** cap its resources (CPU, memory), and a **layered, copy-on-write filesystem** gives it a portable image. Saying "namespaces + cgroups + union filesystem" is the difference between using Docker and understanding it — and it explains every property: instant startup (it's just a process), density (no guest OS), and "works on my machine" being solved (the image carries userland + deps).

---

## 1. The concepts in dependency order

- **Image = a stack of read-only layers.** Each Dockerfile instruction creates a layer (a filesystem diff). Layers are content-addressed and **shared**: 50 services on one JRE base image store that base once. A container adds one thin **writable layer** on top (copy-on-write) — which is why container-local writes are ephemeral and [state belongs outside](../../01-foundations/scalability/README.md) (volumes, databases).
- **Layer caching is the build-speed mechanic:** a change invalidates its layer *and everything after it*. Hence the ordering rule — dependencies first, code last:

```dockerfile
# Multi-stage build for a Java service: build tools never reach production.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline        # <-- cached until pom.xml changes
COPY src ./src
RUN mvn -q package -DskipTests       # <-- only this reruns on code change

FROM eclipse-temurin:17-jre-alpine   # small runtime-only base
WORKDIR /app
COPY --from=build /app/target/app.jar app.jar
RUN adduser -D appuser && chown appuser /app
USER appuser                         # never run as root
EXPOSE 8080
HEALTHCHECK CMD wget -qO- localhost:8080/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

- **Multi-stage builds** (above): compile in a fat image, ship a slim one — smaller attack surface, faster pulls. **`MaxRAMPercentage`** matters: the JVM must size its heap from the *cgroup* memory limit, or the kernel OOM-kills the container — the classic "Java in Docker" incident.
- **Networking & compose:** containers on a user-defined bridge network resolve each other by name (`app` can reach `redis:6379`) — DNS-based [service discovery](../../07-microservices/service-discovery/README.md) in miniature. `docker compose up` declares a multi-container dev stack in one YAML.
- **Registry:** images are pushed/pulled by tag (`repo/app:1.4.2`); tags are mutable, **digests** are not — production pins digests or immutable tags ([deployment discipline](../../07-microservices/deployment-patterns/README.md): "what exactly is running?").

## 2. Installation

```bash
# Linux (the real environment; on macOS/Windows use Docker Desktop)
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER && newgrp docker
docker run hello-world
```

## 3. The command toolbelt

```bash
docker build -t myapp:1.0 .            # build image from Dockerfile
docker run -d -p 8080:8080 --name app --memory=512m --cpus=1 myapp:1.0
docker ps                              # running containers
docker logs -f app                     # stdout/stderr (log to stdout, always!)
docker exec -it app sh                 # shell inside — the debugging door
docker stats                           # live cgroup usage (the saturation view)
docker inspect app | less              # full config: env, mounts, network
docker compose up -d && docker compose down
docker system prune                    # reclaim disk from dead layers
```

## 4. The from-scratch implementation

You can't create Linux namespaces from pure Java, but you *can* implement Docker's other half. [`MiniDocker.java`](MiniDocker.java) implements the **image/layer model**: content-addressed layers, a union-filesystem view (upper layers shadow lower), image build with **layer caching** (unchanged instructions reuse cached layers — watch a rebuild skip work exactly like `docker build`), copy-on-write container layers, and a tiny registry with tags-vs-digests. The demo rebuilds an image after a "code change" and shows which layers were cache hits — the mechanic that dictates real Dockerfile ordering.

## 5. Interview soundbites

- "A container is a process with namespaces for isolation, cgroups for limits, and a copy-on-write layered image — not a VM; that's why it starts in milliseconds."
- "Dockerfile order is cache strategy: dependencies before code, so a code change only rebuilds the last layers."
- "Multi-stage builds ship a JRE-only image — the build chain never reaches production."
- "The JVM must be cgroup-aware (`MaxRAMPercentage`) or the OOM killer teaches you about it in production."
- "Containers are ephemeral by construction — the writable layer dies with them; state lives in volumes or [external stores](../../02-building-blocks/databases/sql-vs-nosql/README.md)."

**Related:** [Kubernetes](../kubernetes/README.md) · [DevOps & CI/CD](../devops-cicd/README.md) · [Deployment Patterns](../../07-microservices/deployment-patterns/README.md) · [Linux Mastery](../linux-mastery/README.md)
