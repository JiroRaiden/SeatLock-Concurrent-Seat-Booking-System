# Deployment

What running SeatLock on AWS looks like, why this topology rather than the
obvious alternatives, and — at the end — an honest list of what it is not ready
for. Configuration facts come from `backend/src/main/resources/application.yml`,
`application-prod.yml`, `frontend/nginx.conf`, `frontend/Dockerfile` and
`docker-compose.yml`.

**Read this first:** this repository has never been deployed to AWS. What
follows is a design and a runbook, written from the configuration that exists,
not a description of something currently running. Where a piece is missing —
and one important one is — it says so.

---

## Topology

```
                          Route 53
                             |
          +------------------+-------------------+
          |                                      |
          v                                      v
  app.seatlock.example                   api.seatlock.example
          |                                      |
  +-------+--------+                     +-------+--------+
  |   CloudFront   |                     | Application    |
  |   (TLS, ACM)   |                     | Load Balancer  |
  |   OAC to S3    |                     | (TLS, ACM)     |
  +-------+--------+                     +-------+--------+
          |                                      |  HTTP :8080
          v                                      |  X-Forwarded-For OVERWRITTEN
  +----------------+                             |
  |  S3 bucket     |                             |
  |  (private,     |          =========== PUBLIC SUBNETS ==========
  |   no website   |                             |
  |   endpoint)    |          ========== PRIVATE SUBNETS =========
  |  dist/ from    |                             v
  |  Vite build    |               +-----------------------------+
  +----------------+               |  EC2  t4g.small             |
                                   |  Amazon Linux 2023          |
                                   |  systemd: java -jar         |
                                   |    seatlock-1.0.0.jar       |
                                   |  SPRING_PROFILES_ACTIVE=prod|
                                   +------+---------------+------+
                                          |               |
                     HikariCP, max 20     |               |  Lettuce, max 16
                                          v               v
                          +-----------------------+  +----------------------+
                          | RDS PostgreSQL 16     |  | ElastiCache Redis 7  |
                          | db.t4g.micro          |  | cache.t4g.micro      |
                          | Multi-AZ (or not,     |  | encryption in transit|
                          |  see cost)            |  | AUTH token required  |
                          | encrypted (KMS)       |  | maxmemory-policy     |
                          | automated backups     |  |   noeviction         |
                          | NOT publicly          |  | NOT publicly         |
                          |   accessible          |  |   accessible         |
                          +-----------------------+  +----------------------+

  Secrets Manager / SSM Parameter Store  --> injected into the systemd unit
    /seatlock/prod/JWT_SECRET
    /seatlock/prod/DB_PASSWORD
    /seatlock/prod/REDIS_PASSWORD

  CloudWatch Logs  <-- journald via the CloudWatch agent
  CloudWatch Alarms <-- 5xx rate, ALB target health, RDS connections, Redis evictions
```

Two hostnames, two origins. That is not incidental — it is why CORS exists in
`SecurityConfig` at all, and why the refresh token cannot be an httpOnly cookie
without more infrastructure (`docs/02-security.md`, section 18).

---

## Why this and not ECS Fargate

Fargate is the more "modern" answer and would be the right call at a larger
size. It is not chosen here for reasons that are specific rather than
ideological:

- **There is no backend container image yet.** `docker-compose.yml`'s `api`
  service (profile `full`) builds `./backend` with `dockerfile: Dockerfile`, and
  **that file does not exist in this repository**. The frontend has a proper
  multi-stage Dockerfile; the backend does not. Fargate is container-only, so
  choosing it means writing that image first. A JAR on EC2 runs today.
- **Cost at this size.** One Fargate task at 0.5 vCPU / 1 GB runs continuously
  and bills continuously. A `t4g.small` is comparable or cheaper, and a
  `t3.micro`/`t4g.micro` sits inside the 12-month free tier. For a portfolio
  project with near-zero steady traffic, the always-on task is the wrong shape.
- **Operational surface.** Fargate brings a task definition, a service, an ECR
  repository, a task execution role, a separate task role, awslogs
  configuration and a deployment circuit breaker. Every one of those is a thing
  to get right. `scp` a JAR and `systemctl restart` is a thing you can debug at
  2am with a phone.

**What would change the answer.** The moment you want more than one instance,
Fargate wins outright — and note that this application has two properties that
already care about instance count: `RateLimitFilter` keeps buckets in the JVM
heap, so three instances mean 3× the configured limit; and `HoldExpiryReaper`
runs on every instance, which is wasteful-but-harmless today and stops being
harmless the moment a scheduled job is not idempotent. Both are documented in
their own class javadocs, with their fixes named (Bucket4j's Redis backend;
ShedLock).

## Why not Render / Railway / Fly.io

They are genuinely good, and for a demo link they are the pragmatic choice: push
a repo, get a URL and a managed Postgres, no VPC to design.

They are not used here because the interesting part of this project is the part
they hide. The security-group story below, the public/private subnet split, the
`X-Forwarded-For` requirement, the readiness-versus-liveness distinction — those
are the questions an infrastructure interview asks, and a PaaS answers all of
them for you invisibly. Also: managed Redis on those platforms is usually an
add-on with its own pricing, and network isolation between the app and its
database is not something you configure or can reason about.

The honest summary: **Render is the better choice for shipping this; AWS is the
better choice for understanding it.** If the goal were a live demo URL rather
than a deployment you can defend line by line, Render would be the answer.

---

## Networking and security groups

| Resource | Subnet | Inbound | From |
|---|---|---|---|
| CloudFront | (edge) | 443 | the internet |
| S3 bucket | — | none | CloudFront only, via Origin Access Control |
| ALB | public | 443 | the internet (80 → redirect to 443) |
| EC2 (API) | **private** | 8080 | the ALB's security group **only** |
| EC2 (API) | private | 22 | nothing — use SSM Session Manager |
| RDS Postgres | **private** | 5432 | the EC2 security group **only** |
| ElastiCache Redis | **private** | 6379 | the EC2 security group **only** |

The rule that makes this readable: **security groups reference other security
groups, never CIDR blocks.** `allow 5432 from sg-api` keeps meaning the right
thing when the instance is replaced, when a second instance is added, and when
the subnet is renumbered. `allow 5432 from 10.0.1.0/24` is a comment that
happens to be enforced, and it rots.

### Why RDS and ElastiCache must never be publicly reachable

This is not a "best practice" bullet; each one is a specific, cheap attack.

- **Postgres.** An internet-facing 5432 is scanned continuously. Once found, the
  attacker gets unlimited offline password guessing against a service that has
  no rate limiting, no lockout and no alerting. Success means the entire
  database — every user row, every booking. And note what is *in* those tables:
  BCrypt hashes (crackable offline at leisure), and refresh-token SHA-256
  hashes. `docs/02-security.md` argues at length that a leaked `refresh_tokens`
  table is survivable **because the raw tokens are not stored**; that argument
  is about a leak, not about handing an attacker a live SQL connection.
- **Redis.** Worse, because Redis's failure mode is not "authenticate first".
  A misconfigured, exposed Redis is the single most reliably exploited data
  store on the internet: unauthenticated by default, and `CONFIG SET dir` plus
  `SAVE` has historically been used to write an SSH key straight onto the host.
  Even fully locked down, an attacker who can reach 6379 can `FLUSHALL` and
  release every live seat hold at once mid-drop.

So: `PubliclyAccessible = false` on RDS, no public subnet for the cache subnet
group, and no route to an internet gateway from the private subnets. Outbound
internet for the EC2 instance (package updates, the payment provider) goes
through a NAT gateway — or, if the NAT's hourly charge is not justified, through
VPC endpoints for the specific AWS services needed. Encryption in transit on
both stores; encryption at rest with KMS on both.

The API instance itself sits in a private subnet with the ALB in front. If that
is more VPC than a portfolio budget wants, the reduced version is a single
public-subnet EC2 with a security group that allows 443 from the internet and
nothing else, and RDS/ElastiCache still private. What must not be reduced is
the second half of that sentence.

---

## Secrets

The rule enforced in `application.yml` is: **no secret has a default value.**

```yaml
seatlock:
  security:
    jwt:
      secret: ${JWT_SECRET}          # note: no ":-fallback"
```

`${JWT_SECRET}` with no fallback means a missing environment variable is a
**startup failure**, not a silent boot with a weak key. That is deliberate. A
service that boots with a guessable signing key behaves completely normally
right up until somebody mints their own admin token, and nothing about its
behaviour reveals the problem. Failing loudly at boot converts an invisible
security hole into an obvious operational one. `JwtService` doubles down: it
decodes the Base64 secret at construction and throws if it is under 32 bytes,
because HS256 with a short key is brute-forceable offline.

`application-prod.yml` follows the same pattern for Redis, and *tightens* rather
than supplies: the base file allows `${REDIS_PASSWORD:}` (empty is fine for a
local container), and prod overrides it with `${REDIS_PASSWORD}` — no fallback —
so a passwordless production Redis cannot start.

The three secrets live in SSM Parameter Store as `SecureString` (free, and
sufficient) or Secrets Manager (paid, adds managed rotation):

```
/seatlock/prod/JWT_SECRET       <- openssl rand -base64 32
/seatlock/prod/DB_PASSWORD
/seatlock/prod/REDIS_PASSWORD
```

The instance role gets `ssm:GetParameter` scoped to `/seatlock/prod/*` and
`kms:Decrypt` on the key that encrypts them — not `ssm:*` on `*`. Fetch them in
an `ExecStartPre` script that writes an `EnvironmentFile` with mode 600 owned by
the service user, or read them at boot into the unit's environment.

The residual risk, stated in `docs/02-security.md` and worth repeating: an
environment variable is visible in `/proc/<pid>/environ` to anyone on the box and
is often captured in crash dumps and process listings. Secrets Manager with
runtime fetching and rotation is the better answer; env vars are what this
application currently reads.

Everything non-secret has a sensible default and is listed in `.env.example`,
which is committed precisely so a new operator knows what exists without ever
seeing a value. `.gitignore` opens with `.env`, `.env.*` (with `!.env.example`
re-included), `*.pem`, `*.p12`, `*.jks`, `**/secrets/`.

---

## CI/CD

No pipeline exists in this repository today. This is the shape it should take —
GitHub Actions, one workflow, fail fast on the cheap stages:

```
 push / PR
    |
 1. Build + fast tests        mvn -f backend/pom.xml -B test
    |                         (Surefire, no Docker; seconds)
 2. Frontend gates            npm ci && npm run typecheck && npm run lint
    |                         (--max-warnings 0)
 3. Integration tests         mvn -f backend/pom.xml -B verify
    |                         Testcontainers; GitHub runners have Docker.
    |                         This is the 50-thread concurrency test.
    |                         Publish JaCoCo from target/site/jacoco.
 4. Security scans            mvn dependency-check, npm audit, Trivy on images
    |                         (none of these are wired up today)
    |
    +-- PR stops here. Only main continues. --+
    |
 5. Package                   mvn -B package -DskipTests   -> seatlock-1.0.0.jar
    |                         npm run build                -> frontend/dist
    |
 6. Publish                   JAR -> S3 artifact bucket (or ECR, once a backend
    |                                 Dockerfile exists)
    |                         dist -> aws s3 sync + CloudFront invalidation
    |
 7. Deploy API                SSM RunCommand or CodeDeploy:
    |                           fetch the JAR, systemctl restart seatlock,
    |                           wait for /actuator/health/readiness
    |
 8. Smoke test                curl the health endpoint and one public GET;
                              roll back on failure
```

Notes on the stages that are not obvious:

- **Stage 3 needs Docker**, and GitHub-hosted Linux runners provide it. This is
  the stage that actually proves the project's claim, so it must not be
  `continue-on-error`.
- **The CloudFront invalidation in stage 6 only needs `/index.html`.** Vite
  fingerprints every asset (`app-4f2c9a1b.js`), so `/assets/*` is immutable and
  cached for a year by `nginx.conf`'s policy — a new deploy produces new
  filenames, and there is nothing to invalidate. `index.html` is the opposite
  case: its name never changes and its contents do, which is why it is served
  `no-cache, no-store, must-revalidate`. Invalidating `/*` on every deploy is
  the common mistake; it is slower and costs more.
- **`VITE_API_BASE_URL` is baked in at build time**, not read at runtime — a
  static site has no runtime environment. So stage 5 must build the frontend
  once per environment, with the right API origin. `frontend/Dockerfile` takes
  it as a `--build-arg` for the same reason.
- **Flyway runs at application startup**, not as a pipeline stage. It is
  configured with `baseline-on-migrate: false` and `validate-on-migrate: true`,
  so pointing at the wrong database, or a checksum that does not match a
  migration already applied, is a hard startup failure rather than a silent
  schema change. That is the desired behaviour: the deploy fails, the old
  process keeps serving.

---

## First deployment: a runbook

Assumes an AWS account, the CLI configured, and a VPC with public and private
subnets.

**1. Generate the signing key and store the secrets.**

```bash
openssl rand -base64 32          # 32 bytes -> passes JwtService's length check

aws ssm put-parameter --name /seatlock/prod/JWT_SECRET \
  --type SecureString --value "<the value above>"
aws ssm put-parameter --name /seatlock/prod/DB_PASSWORD \
  --type SecureString --value "<a long random password>"
aws ssm put-parameter --name /seatlock/prod/REDIS_PASSWORD \
  --type SecureString --value "<a long random password>"
```

**2. Create the data stores, private.**

```bash
aws rds create-db-instance \
  --db-instance-identifier seatlock-prod \
  --engine postgres --engine-version 16 \
  --db-instance-class db.t4g.micro --allocated-storage 20 \
  --master-username seatlock --manage-master-user-password \
  --db-subnet-group-name seatlock-private \
  --vpc-security-group-ids sg-rds \
  --no-publicly-accessible --storage-encrypted \
  --backup-retention-period 7

aws elasticache create-replication-group \
  --replication-group-id seatlock-prod \
  --replication-group-description "SeatLock holds" \
  --engine redis --engine-version 7.1 \
  --cache-node-type cache.t4g.micro --num-node-groups 1 \
  --cache-subnet-group-name seatlock-private \
  --security-group-ids sg-redis \
  --transit-encryption-enabled --at-rest-encryption-enabled \
  --auth-token "<the REDIS_PASSWORD value>"
```

Set the ElastiCache parameter group's `maxmemory-policy` to **`noeviction`** —
the same as `docker-compose.yml` sets locally. The default (`volatile-lru`)
would evict live seat holds under memory pressure, silently releasing seats
somebody is paying for. `noeviction` turns that into a write error instead,
which is loud and correct.

**3. Build.**

```bash
mvn -f backend/pom.xml clean verify        # runs the full suite; needs Docker
cd frontend && npm ci && VITE_API_BASE_URL=https://api.seatlock.example npm run build
```

**4. Ship the frontend.**

```bash
aws s3 sync frontend/dist s3://seatlock-web --delete
aws cloudfront create-invalidation --distribution-id E123ABC --paths /index.html
```

**5. Ship the API.**

```bash
scp backend/target/seatlock-1.0.0.jar ec2-user@<host>:/opt/seatlock/
```

`/etc/systemd/system/seatlock.service`:

```ini
[Unit]
Description=SeatLock API
After=network-online.target

[Service]
User=seatlock
WorkingDirectory=/opt/seatlock
EnvironmentFile=/etc/seatlock/env          # written by ExecStartPre from SSM, mode 600
ExecStart=/usr/bin/java -XX:MaxRAMPercentage=75 -jar /opt/seatlock/seatlock-1.0.0.jar
SuccessExitStatus=143                      # 143 = SIGTERM; a graceful stop is not a failure
Restart=on-failure
RestartSec=5
TimeoutStopSec=40                          # > spring.lifecycle timeout of 25s

[Install]
WantedBy=multi-user.target
```

The environment file carries at minimum:

```
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://seatlock-prod.xxxx.rds.amazonaws.com:5432/seatlock
DB_USERNAME=seatlock
DB_PASSWORD=...
REDIS_HOST=seatlock-prod.xxxx.cache.amazonaws.com
REDIS_PORT=6379
REDIS_PASSWORD=...
JWT_SECRET=...
CORS_ALLOWED_ORIGINS=https://app.seatlock.example
```

`CORS_ALLOWED_ORIGINS` must be the exact CloudFront/Route 53 origin.
`SecurityProperties.Cors.allowedOrigins` is `@NotEmpty`, so the application will
not start without it — but it will start happily with the *wrong* value, and
every browser call will then fail preflight. It is the single most common
production configuration mistake in this stack.

**6. Verify.**

```bash
curl -fsS https://api.seatlock.example/actuator/health/readiness   # {"status":"UP"}
curl -fsS 'https://api.seatlock.example/api/v1/events?size=1'
```

Then open the site, register, book a seat, and confirm with
`tok_demo_success`.

**One more that is easy to miss:** `frontend/nginx.conf` sets
`connect-src 'self'` in its CSP. That is correct when nginx proxies `/api` to
Spring on the same origin. In the CloudFront + S3 topology above the API is a
**different** hostname, so `connect-src` must name it explicitly
(`connect-src 'self' https://api.seatlock.example`) or every API call the SPA
makes is blocked by the browser with no server-side error to find. The comment
in `nginx.conf` says exactly this; it is repeated here because it fails at
runtime, in the browser, and looks like a network problem.

---

## Graceful shutdown, health checks, and readiness ≠ liveness

### Graceful shutdown

`application-prod.yml`:

```yaml
server:
  shutdown: graceful
spring.lifecycle:
  timeout-per-shutdown-phase: 25s
```

On `SIGTERM`, Tomcat stops accepting new connections and lets in-flight requests
finish, up to 25 seconds. Without it, a deploy can kill a request between "seats
held in Redis" and "PENDING booking written to Postgres" — a survivable state
(the hold expires in 8 minutes; see `docs/04-booking-lifecycle.md`) but an
entirely avoidable one, and it happens on *every* deploy rather than on a rare
crash. The confirm path is worse: a kill between the DB commit and the payment
capture is the one genuinely bad window in the system, and graceful shutdown
removes deploys as a way to hit it.

`TimeoutStopSec=40` in the unit file must exceed the 25 s Spring timeout, or
systemd will `SIGKILL` the process mid-drain and undo the whole point.

### The two probes

`application.yml` sets `management.endpoint.health.probes.enabled: true`, which
exposes:

- `/actuator/health/liveness`
- `/actuator/health/readiness`

`SecurityConfig` makes `/actuator/health` and `/actuator/health/**` `permitAll()`
— a load balancer cannot present a bearer token — while everything else under
`/actuator/**` requires `ROLE_ADMIN`.

### Why they are not the same question

**Liveness asks: is this process broken beyond recovery? If no, do not restart
it.** **Readiness asks: should traffic be sent here right now?**

Conflating them causes a specific and nasty failure. Suppose the health check
includes the database, and the database has a brief hiccup:

- Treated as **readiness**: every instance is pulled out of the load-balancer
  pool, requests queue or fail fast, and the moment Postgres recovers every
  instance comes straight back with its connection pool and JIT state intact.
- Treated as **liveness**: the orchestrator concludes every instance is broken
  and **restarts them all** — during a database outage. Now, when Postgres
  recovers, it is hit by a cold-start stampede of instances all opening 20
  connections at once and all running Flyway validation, which is the fastest
  way to turn a thirty-second blip into a twenty-minute outage. Restarting a
  process never fixes a sick database; it only removes the warm one that was
  ready to serve.

So the rules are:

- **Liveness** should check almost nothing — that the JVM responds. Spring's
  default liveness state is exactly that: it does not consult the datasource.
  Wire it to the ALB *only* as an EC2/ASG instance health check, if at all.
- **Readiness** should check the dependencies a request actually needs — the
  datasource and Redis, which Boot's auto-configured health indicators include.
  This is what the **ALB target group** polls, and it is what
  `docker-compose.yml`'s `api` healthcheck polls
  (`wget -qO- http://localhost:8080/actuator/health/readiness`).

Readiness also matters at *startup*: Spring reports `OUT_OF_SERVICE` until the
context is fully refreshed, so the ALB does not route to an instance that is
still running Flyway. Give the target group a `start_period` equivalent — the
compose file uses `start_period: 30s` with 12 retries at 10 s — because a cold
JVM plus migrations takes longer than a steady-state check should tolerate.

`application-prod.yml` sets `endpoint.health.show-details: never` (the base
profile uses `when-authorized`), so the public probe returns `{"status":"UP"}`
and nothing about which component is failing.

---

## The proxy note that actually matters

**The load balancer must _overwrite_ `X-Forwarded-For`, not append to it.**

`application.yml` sets `server.forward-headers-strategy: framework`, which
installs Spring's `ForwardedHeaderFilter`. That filter parses `X-Forwarded-For`
and rewrites `request.getRemoteAddr()` to the client IP it finds.
`RateLimitFilter.clientKey()` then keys its Bucket4j buckets on
`getRemoteAddr()` and — deliberately — never reads the header itself.

That is the correct layering, and it has exactly one hard prerequisite. If the
proxy **appends** to a client-supplied header, then a request arriving with:

```
X-Forwarded-For: 203.0.113.99
```

leaves the ALB as:

```
X-Forwarded-For: 203.0.113.99, <real client IP>
```

and the standard parse takes the **left-most** entry as the originating client.
The attacker has just chosen their own rate-limit bucket. Rotate that value per
request and the limiter is not merely weakened, it is **entirely bypassed** —
including on `/auth/login`, where each attempt costs ~250 ms of cost-12 BCrypt,
so the limiter is also the CPU-exhaustion defence. Worse, the attacker can put
a *real* address in there and get an innocent third party throttled.

Concretely:

- **AWS ALB appends.** It preserves whatever the client sent and adds the real
  source IP on the right. So on an ALB the safe reading is the **right-most**
  entry, not the left-most — and `ForwardedHeaderFilter` does not know that.
  The two workable fixes are: (a) put **AWS WAF** in front with a rule that
  strips `X-Forwarded-For` from inbound requests before the ALB sees it, or
  (b) set `forward-headers-strategy: none` and derive the client IP from the
  right-most entry yourself in `clientKey()`.
- **CloudFront** sets `CloudFront-Viewer-Address` (and can be configured to
  overwrite `X-Forwarded-For` via a viewer-request function), which is a
  trustworthy source because the client cannot forge it past the edge.
- **nginx**: use `proxy_set_header X-Forwarded-For $remote_addr;`
  (**overwrite**), not the reflexive `$proxy_add_x_forwarded_for`
  (**append**), unless you are also configuring `real_ip_recursive` and
  `set_real_ip_from` correctly.

The general rule: **a rate limiter is only as trustworthy as the proxy
configuration underneath it**, and "we set `forward-headers-strategy: framework`"
is a statement about the application only. `RateLimitFilter`'s javadoc and
`docs/02-security.md` section 12 both point here; this is the section they point
to.

---

## Rough monthly cost

Approximate, `ap-south-1`, at portfolio traffic. Prices move; treat these as
order-of-magnitude.

| Item | Inside 12-month free tier | After free tier |
|---|---|---|
| EC2 `t4g.small` (API) | ~$0 (`t2/t3.micro` free, 750 h) | ~$12 |
| RDS `db.t4g.micro`, 20 GB, single-AZ | ~$0 (750 h + 20 GB free) | ~$13 |
| ElastiCache `cache.t4g.micro` | ~$0 (750 h free, first year) | ~$11 |
| S3 (a few MB of static assets) | ~$0 | <$1 |
| CloudFront (1 TB/month free, permanently) | ~$0 | ~$0 |
| ALB | **not free** — ~$16 + LCU | ~$18 |
| Route 53 hosted zone | ~$0.50 | ~$0.50 |
| ACM certificates | free | free |
| CloudWatch logs (small volume) | ~$0 | ~$1 |
| NAT gateway (if used) | **not free** — ~$32 + data | ~$35 |
| **Total** | **~$17/month** | **~$56/month** |

Two line items dominate and both are avoidable at this size:

- **The ALB (~$16) and the NAT gateway (~$32) are the whole bill.** Drop both —
  put the EC2 instance in a public subnet with a security group allowing only
  443, terminate TLS with Caddy or nginx on the instance using a Let's Encrypt
  certificate, and use VPC endpoints instead of NAT for SSM — and the running
  cost falls to roughly **$1/month in the free tier** and about $25 after it.
  RDS and ElastiCache stay private either way; that is the part not to
  compromise.
- **Multi-AZ RDS doubles the database cost** and is the correct choice for
  anything real. It is not in the estimate above.

---

## What this deployment is not ready for

Written plainly, because a deployment doc that only lists what works is a sales
page.

1. **There is no backend container image.** `docker-compose.yml` references
   `backend/Dockerfile`, which does not exist, so `docker compose --profile full
   up` fails at build. Only the Postgres + Redis half of the compose file works
   today. That is also what blocks the ECS option.
2. **There is no CI pipeline.** Nothing runs the test suite automatically. The
   50-thread concurrency test is the project's central claim and it is currently
   only run by a human remembering to run it.
3. **The payment gateway is a stub.** `StubPaymentGateway` is a plain
   `@Component` with no profile condition, so it is active **in production too**
   — deliberately, and named so nobody can mistake it, but it means this system
   cannot take money. A real provider goes behind the `PaymentGateway` interface
   with the stub moved to `@Profile("stub-payments")`.
4. **No reconciliation for the one bad failure window.** A crash between the
   booking commit and the payment capture gives away a free seat. The fix is an
   outbox table plus a nightly settlement job; see `docs/04-booking-lifecycle.md`.
5. **Rate limiting is per-instance.** Scaling past one instance multiplies every
   limit by the instance count. Fix: Bucket4j's Redis backend.
6. **The reaper runs on every instance.** Harmless today because
   `expireBooking` is idempotent, wasteful at any scale, and a trap if a future
   scheduled job is not. Fix: ShedLock.
7. **Swagger UI and `/v3/api-docs` are `permitAll()`.** A free map of the attack
   surface. Restrict or disable them in production; `docs/02-security.md`
   section 16 says the same.
8. **TLS is not terminated by anything in this repository.** HSTS is emitted by
   an application perfectly happy to serve plaintext. TLS must come from the ALB
   or CloudFront with an ACM certificate, and HTTP must redirect to HTTPS.
9. **No autoscaling, and it is not just a config change.** Items 5 and 6 have to
   be fixed first, or scaling out quietly degrades two correctness-adjacent
   behaviours.
10. **No blue/green or canary.** The runbook above is a restart-in-place, which
    means a window of seconds where the single instance is not serving. Graceful
    shutdown makes that window clean, not absent.
11. **No tested restore.** Backups that have never been restored are a belief,
    not a backup. Enable PITR on RDS and actually practise a restore.
12. **No WAF, no dependency or container scanning, no audit log, and no
    distributed tracing.** `docs/02-security.md`'s closing section lists all of
    these with reasons.
13. **Observability stops at `/actuator/prometheus`.** Nothing scrapes it, there
    is no dashboard, and there are no alerts. The one custom metric that exists,
    `seatlock.holds.expired`, is the number that tells you whether the 8-minute
    TTL is right — and nobody is looking at it.
