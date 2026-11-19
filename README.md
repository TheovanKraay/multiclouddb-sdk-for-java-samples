# Multicloud DB SDK — Sample Applications

Sample applications demonstrating the [Multicloud DB SDK for Java](https://github.com/microsoft/multiclouddb-sdk-for-java) provider-portable API.  
Each sample runs against **Azure Cosmos DB**, **Amazon DynamoDB**, or **Google Cloud Spanner** — switch providers by changing a single `.properties` file.

| Sample | Description | Port | Guide |
|--------|-------------|------|-------|
| **Portable CRUD + Query** | Minimal end-to-end CRUD and query sample | — | [below](#portable-crud--query-sample) |
| **Change Feed (one-shot)** | Writer + consumer demo showing CREATE / UPDATE / DELETE events | — | [below](#change-feed-samples) |
| **Change Feed Watcher** | Long-running consumer that prints events as you add/edit/delete items in the portal | — | [below](#change-feed-samples) |
| **Risk Analysis Platform** | Multi-tenant portfolio risk analytics with executive dashboard | `8090` | [README-risk-platform.md](README-risk-platform.md) |
| **TODO App** | Simple CRUD web app with browser UI | `8080` | [README-todo-app.md](README-todo-app.md) |

---

## Prerequisites

### 1 — Java 17+

```bash
java -version   # must be 17 or later
```

### 2 — Build this project

This branch uses the **unreleased** serialization API. [Install the matching
SDK revision](#sdk-version) before compiling the samples.

```bash
# In this repo root
mvn compile
```

---

## Portable CRUD + Query Sample

Runs the same CRUD and query code against any provider. Switch by pointing `multiclouddb.config` at a different `.properties` file.

### Against Cosmos DB (local emulator)

1. Start the [Azure Cosmos DB Emulator](https://learn.microsoft.com/azure/cosmos-db/local-emulator)
2. Run:

```bash
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.PortableCrudQuerySample
```

### Against DynamoDB (local)

1. Start [DynamoDB Local](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.html)
2. Run:

```bash
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.PortableCrudQuerySample \
    -Dmulticlouddb.config=todo-app-dynamo.properties
```

### Against Spanner (emulator)

1. Start the [Cloud Spanner Emulator](https://cloud.google.com/spanner/docs/emulator)
2. Run:

```bash
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.PortableCrudQuerySample \
    -Dmulticlouddb.config=todo-app-spanner.properties
```

---

## Running the Samples

> **Important:** Always run `mvn` from the **repo root** (where `pom.xml` lives). Do **not** use `-pl` — this is a standalone single-module project.

### Risk Analysis Platform

```bash
# Against Cosmos DB (local emulator)
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp \
    -Drisk.config=risk-platform-cosmos.properties

# Against Cosmos DB (cloud)
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp \
    -Drisk.config=risk-platform-cosmos-cloud.properties

# Against DynamoDB (local)
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp \
    -Drisk.config=risk-platform-dynamo.properties

# Against DynamoDB (cloud)
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp \
    -Drisk.config=risk-platform-dynamo-cloud.properties
```

### TODO App

```bash
# Against Cosmos DB (local emulator)
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.todo.TodoApp

# Against DynamoDB (local)
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.todo.TodoApp \
    -Dmulticlouddb.config=todo-app-dynamo.properties
```

### Change Feed Samples

> ℹ️ **Provider support:** The one-shot (`ChangeFeedSample`) and continuous
> (`ChangeFeedWatcherSample`) change-feed samples run on **Azure Cosmos DB**
> and **Amazon DynamoDB**. These samples do not target the Spanner change feed.
> Extended retention (`ChangeFeedExtendedRetentionSample`) remains **Cosmos-only**
> because DynamoDB Streams are fixed at 24h.

Three samples demonstrate the SDK's pull-mode change feed. They use the dedicated database
`multiclouddb-sdk-for-java-changefeed` and container `change-feed-demo` (see
`src/main/resources/change-feed-cosmos*.properties` and
`src/main/resources/change-feed-dynamo*.properties`).

> The change-feed sample sources live under
> `src/main/java/com/multiclouddb/samples/changefeed/` and are in the
> `com.multiclouddb.samples.changefeed` package — see
> [`README-change-feed.md`](README-change-feed.md) for the deep dive.

**Provisioning notes:**

- **Live Cosmos** — the account must have **Continuous Backup** enabled. When CB
  is on, the All-Versions-and-Deletes (AVAD) change feed (the source of
  CREATE/UPDATE/DELETE events) is available automatically on every container, so
  the samples just call `ensureContainer` for a plain container. Verify CB with
  `az cosmosdb show --query backupPolicy.type -o tsv` (expect `Continuous`).
- **Cosmos emulator** — the emulator has no CB, so the samples pre-provision the
  container with an AVAD `ChangeFeedPolicy` and a 10-minute retention (the
  emulator's hard ceiling) on first run.
- **DynamoDB (local or AWS)** — change feed is backed by DynamoDB Streams.
  `ensureContainer` creates the table but does not enable streams, so the samples
  enable a `NEW_AND_OLD_IMAGES` stream on the table after creating it. Only
  changes committed *after* the stream is enabled are surfaced.

**First-time setup for live cloud accounts (one-time per checkout):**

Cloud configs (`*-cloud.properties`) are gitignored; only the `.template` files
ship with the repo. `ConfigLoader` reads configs from the fat-jar classpath, so
the runtime file must live under `src/main/resources/` *before* you run
`mvn package`. Copy the template for the provider you want, fill it in, then
rebuild the fat jar.

**Cosmos DB (Azure):**

```bash
cp src/main/resources/change-feed-cosmos-cloud.properties.template \
   src/main/resources/change-feed-cosmos-cloud.properties
# edit change-feed-cosmos-cloud.properties to fill in endpoint + key
mvn package -DskipTests
```

**DynamoDB (AWS):**

```bash
cp src/main/resources/change-feed-dynamo-cloud.properties.template \
   src/main/resources/change-feed-dynamo-cloud.properties
# edit change-feed-dynamo-cloud.properties to set multiclouddb.connection.region
# credentials come from the default AWS provider chain — make sure
#   `aws sts get-caller-identity` succeeds first (e.g. `aws configure` / `aws sso login`)
mvn package -DskipTests
```

After this one-time copy you can re-use the resulting fat jar for the
change-feed samples below.

> **DynamoDB IAM permissions.** The principal needs `dynamodb:CreateTable`,
> `DescribeTable`, `UpdateTable` (to enable the stream), `PutItem` / `UpdateItem`
> / `DeleteItem`, plus the Streams actions `DescribeStream`, `GetShardIterator`,
> and `GetRecords`. A live table uses on-demand billing — delete it when you're
> done to avoid charges.

#### 1. One-shot change feed demo (`ChangeFeedSample`)

Runs a writer thread that produces a fixed CREATE / UPDATE / DELETE sequence,
drains the change feed, then exits. Useful for validating that change feed is
wired up end-to-end.

**macOS / Linux:**

```bash
# Live Cosmos
mvn package -DskipTests
java -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedSample

# Cosmos emulator (default)
java -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedSample

# DynamoDB Local (auto-enables a NEW_AND_OLD_IMAGES stream)
java -Dmulticlouddb.config=change-feed-dynamo.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedSample

# Live DynamoDB (AWS) — see "First-time setup" above; creds from the AWS chain
java -Dmulticlouddb.config=change-feed-dynamo-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedSample
```

**Windows (PowerShell):**

> PowerShell mangles unquoted `-D...=...` arguments and does not recognise
> bash-style `\` line continuation. Quote each `-D` arg and use the backtick
> (`` ` ``) for continuation, as shown below.

```powershell
# Live Cosmos
mvn package -DskipTests
java "-Dmulticlouddb.config=change-feed-cosmos-cloud.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedSample

# Cosmos emulator (default)
java -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedSample

# DynamoDB Local (auto-enables a NEW_AND_OLD_IMAGES stream)
java "-Dmulticlouddb.config=change-feed-dynamo.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedSample

# Live DynamoDB (AWS) — see "First-time setup" above; creds from the AWS chain
java "-Dmulticlouddb.config=change-feed-dynamo-cloud.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedSample
```

#### 2. Continuous watcher (`ChangeFeedWatcherSample`)

Long-running consumer with no built-in writes. Start it, then add, edit, or
delete items in the target `change-feed-demo` container/table (e.g. the Azure
Portal **Data Explorer** for Cosmos, or the AWS console / `aws dynamodb` CLI for
DynamoDB) — each operation prints a `CREATE` / `UPDATE` / `DELETE` line on the
console within the poll interval (default 1 second). Press **Ctrl+C** to stop;
the watcher prints a final event tally.

**macOS / Linux:**

```bash
# Live Cosmos
mvn package -DskipTests
java -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample

# Cosmos emulator (default config)
java -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample

# DynamoDB Local (auto-enables a NEW_AND_OLD_IMAGES stream)
java -Dmulticlouddb.config=change-feed-dynamo.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample

# Live DynamoDB (AWS) — see "First-time setup" above; creds from the AWS chain
java -Dmulticlouddb.config=change-feed-dynamo-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample

# Override the poll interval (milliseconds; default 1000)
java -Dchangefeed.poll.intervalMs=500 \
     -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
```

**Windows (PowerShell):**

```powershell
# Live Cosmos
mvn package -DskipTests
java "-Dmulticlouddb.config=change-feed-cosmos-cloud.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample

# Cosmos emulator (default config)
java -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample

# DynamoDB Local (auto-enables a NEW_AND_OLD_IMAGES stream)
java "-Dmulticlouddb.config=change-feed-dynamo.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample

# Live DynamoDB (AWS) — see "First-time setup" above; creds from the AWS chain
java "-Dmulticlouddb.config=change-feed-dynamo-cloud.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample

# Override the poll interval (milliseconds; default 1000)
java "-Dchangefeed.poll.intervalMs=500" `
     "-Dmulticlouddb.config=change-feed-cosmos-cloud.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
```

#### 3. Extended retention escape hatch (`ChangeFeedExtendedRetentionSample`)

Opts into `ChangeFeedConfig.extendedRetention(Duration.ofDays(7))` and attempts
to build a client. Succeeds on Cosmos (which declares
`Capability.EXTENDED_CHANGE_FEED_HISTORY`). Use this to verify extended
retention configuration and test cursor persistence beyond 24 hours. See
[`README-change-feed.md`](README-change-feed.md#extended-retention-escape-hatch)
for details.

**macOS / Linux:**

```bash
# Cosmos (live Continuous-Backup account; emulator is rejected)
java -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample
```

**Windows (PowerShell):**

```powershell
# Cosmos (live Continuous-Backup account; emulator is rejected)
java "-Dmulticlouddb.config=change-feed-cosmos-cloud.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample
```

Example output (after creating, then deleting an item in the portal):

```
=== Multicloud DB Change Feed Watcher ===
Provider     : Azure Cosmos DB
Mode         : LIVE
Container    : multiclouddb-sdk-for-java-changefeed/change-feed-demo
Poll interval: 1000 ms

Discovered 1 partition cursor(s) at the live tip.

Watching multiclouddb-sdk-for-java-changefeed/change-feed-demo — go add/update/delete items (e.g., in the Azure Portal Data Explorer).
Press Ctrl+C to stop.

[2026-06-12T19:40:55Z] CREATE MulticloudDbKey{partitionKey=portal-1, sortKey=portal-1}  {"title":"hello","id":"portal-1", ...}
[2026-06-12T19:40:58Z] DELETE MulticloudDbKey{partitionKey=portal-1, sortKey=portal-1}  {}
```


---

## Configuration Files

| File | Provider |
|------|----------|
| `todo-app-cosmos.properties` | Azure Cosmos DB (local emulator) |
| `todo-app-dynamo.properties` | Amazon DynamoDB Local |
| `todo-app-spanner.properties` | Google Cloud Spanner emulator |
| `risk-platform-cosmos.properties` | Cosmos DB (local) |
| `risk-platform-dynamo.properties` | DynamoDB Local |
| `risk-platform-cosmos-cloud.properties.template` | Cosmos DB (cloud) |
| `risk-platform-dynamo-cloud.properties.template` | DynamoDB (cloud) |
| `change-feed-cosmos.properties` | Cosmos DB emulator (data-plane samples + extended-retention build-time gate) |
| `change-feed-cosmos-cloud.properties.template` | Cosmos DB (cloud) — copy to `src/main/resources/change-feed-cosmos-cloud.properties` (gitignored), then fill in endpoint + key |
| `change-feed-dynamo.properties` | Amazon DynamoDB Local (data-plane samples; auto-enables a stream) |
| `change-feed-dynamo-cloud.properties.template` | DynamoDB (cloud) — copy to `src/main/resources/change-feed-dynamo-cloud.properties` (gitignored), then set the region |
| `change-feed-spanner.properties` | ⏳ Reserved for future Spanner change-feed support |
| `change-feed-spanner-cloud.properties.template` | ⏳ Reserved for future Spanner change-feed support |

---

## Build an Executable JAR

```bash
mvn package

java -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.PortableCrudQuerySample
```

---

## Project Structure

```
multiclouddb-samples/
├── pom.xml                             # Standalone Maven build (resolves SDK from ~/.m2)
├── README.md                           # This file
├── README-risk-platform.md             # Risk Analysis Platform guide
├── README-todo-app.md                  # TODO App guide
├── scripts/
│   ├── cleanup-cosmos.sh / .ps1        # Delete Cosmos DB test resources
│   └── cleanup-dynamo.sh / .ps1        # Delete DynamoDB test resources
└── src/main/
    ├── java/com/multiclouddb/samples/
    │   ├── ConfigLoader.java           # Reads .properties config files
    │   ├── PortableCrudQuerySample.java # Minimal CRUD + query demo
    │   ├── riskplatform/               # Risk Analysis Platform sample
    │   └── todo/                       # TODO App sample
    └── resources/
        ├── *.properties                # Provider config files
        └── static/                     # Web UI assets
```

---

## SDK Version

This branch tests the merge of [SDK PR 122](https://github.com/microsoft/multiclouddb-sdk-for-java/pull/122).
Install all modules from that revision into the same local Maven repository used by the samples:

```bash
git clone https://github.com/microsoft/multiclouddb-sdk-for-java.git
cd multiclouddb-sdk-for-java
git checkout --detach 40b2e2ba5075e17e51b74399f074de28f3762f69
mvn clean install -DskipTests
```

Then run `mvn clean verify` in the samples checkout. No `.mvn/maven.config`
override is needed; remove stale local version overrides. The POM selects
`0.1.0-beta.2-SNAPSHOT` for API/Cosmos/Dynamo and `0.1.0-beta.1-SNAPSHOT` for Spanner.

### What changed for a sample author?

| Use case | Released samples (`main`) | This branch |
|---|---|---|
| Writes and queries | Write Maps; get rows as Maps | Same call sites. Input values must now be supported Maps, Lists or scalars. |
| Point reads | `result.document()` is an `ObjectNode` | It is a detached, mutable Map. Map-based callers can edit it directly; the TODO and risk APIs use `valueToTree` where their callers expect Jackson trees. |
| Feed preview | Print a `JsonNode` | `event.data()` is an `Object`. Skip a missing (`null`) payload, but serialize a supplied empty Map as `{}`. |
| Persist/resume a cursor | `cursor.toToken()` / `ChangeFeedCursor.fromToken(token)` | `cursor.toToken(FeedJson::write)` / `ChangeFeedCursor.fromToken(token, FeedJson::read)`. The samples share [one Jackson adapter](src/main/java/com/multiclouddb/samples/changefeed/FeedJson.java); the SDK still owns the v1 envelope. |

Jackson is an application choice, not a requirement for using the client. The
samples already declared it for their HTTP APIs. The callback plumbing only
applies when turning cursors into tokens or restoring them.

---

## License

MIT — see [LICENSE](LICENSE).
