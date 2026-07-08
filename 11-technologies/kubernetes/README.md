# Kubernetes — Declarative Container Orchestration

> **Mental model:** Kubernetes is a **reconciliation machine**. You declare desired state ("3 replicas of orders:v2 behind a stable name"); controllers run **control loops** that continuously compare *desired* vs *actual* and act to converge them. Every feature — self-healing, rolling deploys, autoscaling — is one instance of that single idea. Say "declarative desired state + reconciliation loops" and you've explained Kubernetes; everything else is object names.

---

## 1. The object model in dependency order

- **Pod** — the atom: one or more containers sharing network namespace (localhost) and volumes. Pods are **mortal and disposable**; you almost never create them directly.
- **Deployment → ReplicaSet → Pods** — "keep N replicas of this pod template running." Kill a pod, the ReplicaSet replaces it (self-healing = reconciliation). Update the template (new image tag) and the Deployment orchestrates a **rolling update** — new ReplicaSet scales up as the old drains ([rolling deploy](../../07-microservices/deployment-patterns/README.md) as a built-in, with `maxSurge`/`maxUnavailable`).
- **Service** — a stable virtual IP + DNS name (`orders.prod.svc.cluster.local`) load-balancing over pods matched by **label selector**, membership maintained by **readiness probes** — [service discovery](../../07-microservices/service-discovery/README.md) as platform. Types: `ClusterIP` (internal), `NodePort`, `LoadBalancer` (cloud LB); **Ingress/Gateway API** for L7 HTTP routing at the edge.
- **ConfigMap / Secret** — config and credentials injected as env/files, decoupled from images (same image, every environment — [12-factor](../devops-cicd/README.md)).
- **Probes** — liveness ("restart me"), readiness ("route to me"), startup. The [liveness-vs-readiness distinction](../../07-microservices/service-discovery/README.md) prevents the restart-the-warming-service outage.
- **Requests/limits + HPA** — requests drive *scheduling* (bin-packing), limits drive *cgroup enforcement*; the HorizontalPodAutoscaler scales replicas on CPU/custom metrics ([observability](../../10-security-observability/observability/README.md) feeding control).
- **StatefulSet / PVC** — stable identities + persistent volumes for the stateful minority (databases); **DaemonSet** (one per node — log shippers); **Job/CronJob** (batch).
- **Architecture:** control plane = **API server** (front door, everything is a REST object), **etcd** (the cluster's [Raft](../../05-distributed-systems/consensus-algorithms/raft/README.md)-backed CP store — Kubernetes state *is* an etcd database), **scheduler** (pod→node placement), **controller-manager** (the reconciliation loops); per node = **kubelet** (runs pods) + **kube-proxy** (Service routing).

## 2. The YAML that covers the interview

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: orders }
spec:
  replicas: 3
  selector: { matchLabels: { app: orders } }
  strategy:
    rollingUpdate: { maxSurge: 1, maxUnavailable: 0 }   # zero-downtime rollout
  template:
    metadata: { labels: { app: orders } }
    spec:
      containers:
        - name: orders
          image: registry.local/orders:1.4.2            # immutable tag, not :latest
          resources:
            requests: { cpu: "250m", memory: "256Mi" }  # scheduling
            limits:   { memory: "512Mi" }               # OOM boundary
          readinessProbe: { httpGet: { path: /health/ready, port: 8080 } }
          livenessProbe:  { httpGet: { path: /health/live,  port: 8080 } }
---
apiVersion: v1
kind: Service
metadata: { name: orders }
spec:
  selector: { app: orders }        # membership by label + readiness
  ports: [{ port: 80, targetPort: 8080 }]
```

## 3. Installation & toolbelt

```bash
# Local cluster options: kind (Kubernetes-in-Docker), minikube, k3s
go install sigs.k8s.io/kind@latest && kind create cluster    # or: brew install kind
kubectl apply -f orders.yaml               # declare desired state
kubectl get pods -w                        # watch reconciliation happen
kubectl describe pod orders-xxx            # events: scheduling, probe failures
kubectl logs -f deploy/orders              # logs
kubectl exec -it deploy/orders -- sh       # shell in
kubectl rollout status deploy/orders       # rolling update progress
kubectl rollout undo deploy/orders         # one-command rollback
kubectl scale deploy/orders --replicas=10  # (or let HPA do it)
kubectl delete pod orders-xxx              # watch it come back — self-healing
```

## 4. The from-scratch implementation

[`MiniKubelet.java`](MiniKubelet.java) implements the reconciliation heart: a **desired-state store** (Deployments with replica counts and image versions), an **actual-state store** (running "pods"), and a **control loop** that converges them — replacing crashed pods, scaling up/down, and performing a **rolling update with maxSurge/maxUnavailable semantics** when the image changes, plus a readiness-gated **Service endpoint list**. Kill a pod in the demo and watch the loop restore it; bump the image and watch the rollout wave through.

## 5. Interview soundbites

- "Kubernetes is declarative desired state plus reconciliation loops — self-healing and rollouts are the same mechanism, not separate features."
- "Pods are mortal; Deployments manage replicas; Services give a stable name over readiness-gated, label-selected pods — that's platform-provided discovery."
- "Requests schedule, limits enforce — and etcd, a Raft CP store, is the cluster's source of truth."
- "`rollout undo` exists because the old ReplicaSet is kept — rollback is re-pointing desired state, cheap by design."

**Related:** [Docker](../docker/README.md) · [Service Discovery](../../07-microservices/service-discovery/README.md) · [Deployment Patterns](../../07-microservices/deployment-patterns/README.md) · [Raft](../../05-distributed-systems/consensus-algorithms/raft/README.md) · [DevOps & CI/CD](../devops-cicd/README.md)
