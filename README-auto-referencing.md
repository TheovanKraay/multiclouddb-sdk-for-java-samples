# Application-layer auto-referencing

This experimental sample stores a large logical JSON object as smaller linked
records and reconstructs it through a read-by-key wrapper. It implements no new
SDK feature. It uses the published SDK versions already selected by this repo,
not the serialization redesign PR.

The pattern follows [Cosmos embedding versus referencing guidance](https://learn.microsoft.com/azure/cosmos-db/modeling-data#when-not-to-embed)
and [DynamoDB vertical partitioning guidance](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-use-s3-too.html).
It is a starting point for an application's trade-offs, not a universal document
storage abstraction.

## Run

Use Java 17 and Maven from this repository root. Start the target emulator first.
The sample reuses the existing connection configuration loader:

```powershell
mvn compile exec:java "-Dexec.mainClass=com.multiclouddb.samples.referencing.AutoReferencingSample" "-Dmulticlouddb.config=todo-app-dynamo.properties"

mvn compile exec:java "-Dexec.mainClass=com.multiclouddb.samples.referencing.AutoReferencingSample" "-Dmulticlouddb.config=todo-app-cosmos.properties"
```

For Cosmos, trust the emulator certificate in the JVM running Maven. Do not
disable TLS verification. If you have the Explorer installer-managed truststore,
you can use it for this process:

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djavax.net.ssl.trustStore="C:\path\to\emulator-trust\cacerts" -Djavax.net.ssl.trustStorePassword=changeit'
```

The default database/collection is `multiclouddb-referencing` / `documents`.
These names intentionally ignore the TODO sample's data names. Override with
`-Dreferencing.database=...` and `-Dreferencing.collection=...`. Use a dedicated
collection: the wrapper expects its own format, not existing application records.

Additional options:

| Property | Default | Meaning |
|---|---|---|
| `referencing.mode` | `WHEN_OVERSIZED` | Keep small documents inline; split only when the physical envelope exceeds the budget. `ALWAYS` recursively references nonempty object fields and array entries. |
| `referencing.scenario` | `all` | `large-string`, `deep-24`, `wide-130`, `array-300`, `aggregate-3MiB`, `aggregate-6MiB`, or `unicode-and-types` |
| `referencing.keep` | `false` | Keep the physical records after successful verification, instead of deleting them |

Each run prints its unique partition key, byte counts and fragment counts.
Applications would use the wrapper rather than the SDK directly:

```java
var documents = new AutoReferencingStore(client, address);
var receipt = documents.write("tenant-a", "portfolio-1", objectNode,
        AutoReferencingStore.Mode.WHEN_OVERSIZED);
ObjectNode reconstructed = documents.read("tenant-a", "portfolio-1");
```

`write` is a whole-document replacement, not a merge/patch. A missing logical root
returns `null`; missing children or corrupt data throw explicitly.

## Storage and size policy

- The root uses the caller's partition and sort keys. It is either an inline
  envelope or a manifest pointing to a generation-specific root fragment.
- All child records share the root partition key and collection. Each has a
  unique `_ar_<generation>_<index>` sort key. This prefix is reserved.
- Large objects and arrays are recursively split into balanced groups. A single
  large nested value is referenced; large strings are split at Unicode-safe
  boundaries. Array order, nulls, empty containers and field names are preserved.
- Binary reference nodes keep each reference list bounded, including very wide
  documents. The root manifest does not contain a growing list of all fragments.
- Payloads are **JSON text inside a flat record envelope**. This deliberately
  avoids beta SDK provider-specific removal/injection of `id`/`partitionKey`,
  floating-point conversions and native nested-depth differences. JSON numbers
  use BigInteger/BigDecimal on reconstruction; Java numeric node classes are not
  preserved. Non-finite numbers, binary/POJO nodes and malformed Unicode are rejected.
- **Trade-off:** this also means application fields are not directly queryable or
  independently indexed, even in the inline case. Quotes and escapes add storage
  overhead. This sample chooses faithful point-read reconstruction over queryability.
- Default physical budget: **300 KiB**, including a conservative serialized envelope,
  all three potential key fields, and 1 KiB headroom. The wrapper constructor
  accepts budgets from 4 KiB to 350 KiB. This is not a claim that JSON size equals
  DynamoDB item size. All stored application payloads are strings; the conservative
  envelope estimate avoids complex native nested-number accounting and stays below
  the SDK's 399 KiB validator and DynamoDB's 400 KiB item limit.
- Sample limits: 16 MiB logical JSON, logical depth 64, 8,192 fragments, partition
  keys up to 1,024 UTF-8 bytes, sort keys up to 512 bytes without Cosmos-forbidden
  characters. Oversized field names and layouts exceeding the limits are rejected
  **before any write**. These are experimental bounds, not service limits.
- Plans are built in memory before writing, so peak memory exceeds logical size.
  This is not a streaming implementation and should be benchmarked for real workloads.

## No transactions: publication, consistency and failures

The published `MulticloudDbClient` exposes point CRUD but **no multi-document
transaction/batch API**. Its capability flags describe provider features; they
do not supply a callable transaction operation. This sample therefore uses
ordinary sequential `create` calls for immutable fragments, followed by one
`upsert` of the root manifest. It does not bypass the portable SDK to call native
transactions or pretend that several batches form one transaction.

The native ceilings motivating the tests are:

| Provider | Native limits (not used by this sample) |
|---|---|
| Cosmos | 100 operations and 2 MB payload per transactional batch; same container/partition |
| DynamoDB | 100 actions and 4 MB aggregate items per transaction |
| Spanner | 100 MiB commit size and 80,000 Mutation API mutations including indexes; mutations are not document counts |

See [Cosmos batch limits](https://learn.microsoft.com/azure/cosmos-db/transactional-batch#limitations),
[DynamoDB transactions](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html),
and [Spanner limits](https://cloud.google.com/spanner/quotas).

**Consequences:**

1. All fragments are written before publishing the root. An interrupted fragment
   write leaves the previous root intact (or no root on a first write).
2. Publication is one ordinary point upsert, not compare-and-swap. Concurrent full
   replacements are last-writer-wins and may lose application updates.
3. A reader fetches the root once, then follows only its immutable generation.
   Older generations are retained so concurrent replacement cannot mix versions.
   A checksum and byte count verify complete reconstruction.
4. This is **not** a database-wide snapshot or an atomic multi-record commit.
   Eventually consistent visibility can expose a root before all fragments are
   readable. The wrapper fails explicitly instead of returning partial data;
   applications may retry the **whole read**, with a bounded policy.
5. No automatic write retries or rollback are performed. A timeout can mean that
   the operation committed. On write failure the exception identifies the generation
   and preserves its cause; fragments are retained, including after ambiguous
   root-publication failures. Re-read the root and reconcile before recovery.
6. Old, interrupted and overwritten generations accumulate. The sample intentionally
   has **no automatic garbage collector**, TTL policy or concurrent delete API.
   Safe production cleanup needs reader retention/leases or a maintenance window,
   including all active generations. Never immediately delete the previous generation.
7. Change feeds, native queries, exports and Explorer show **physical records**.
   There is no logical change-feed coalescing, transparent query rewriting, or
   provider-neutral ETag/TTL preservation. Do not set a collection TTL that could
   expire fragments while their root remains readable.

The executable demo removes only its successfully verified records when
`referencing.keep=false`. It can do so because it owns an isolated partition and
has no concurrent readers. On failure it retains records for diagnosis; empty
schemas remain. The integration tests follow the same isolation rule.

## Tests

Fast, deterministic tests without credentials or emulators:

```powershell
mvn test
```

Real provider tests, including write, reconstruction, physical fragment reads,
replacement and cleanup:

```powershell
mvn test "-Dreferencing.integration=true" "-Dmulticlouddb.config=todo-app-dynamo.properties"
mvn test "-Dreferencing.integration=true" "-Dmulticlouddb.config=todo-app-cosmos.properties"
```

The test fixtures all exceed **400 KiB of actual serialized logical JSON**, not
just a nominal character count:

| Shape | Logical size | Purpose |
|---|---|---|
| Single large string | ~600 KiB | Scalar chunking, not just embedded objects |
| 24 nesting levels | ~700 KiB | Deep references and reconstruction |
| 130 embedded sections | ~523 KiB | Wide layout; `ALWAYS` produces more than 100 fragments |
| 300 array objects | ~607 KiB | Array order and grouping |
| 12 large sections | ~3 MiB | Exceeds a single Cosmos transaction payload |
| 16 larger sections | ~6 MiB | Exceeds a single DynamoDB transaction item-size budget |
| Unicode, escapes, precise numbers, reserved names | ~762 KiB | Byte accounting and application-field fidelity |

Unit tests run both modes on every fixture and check physical budgets, small
inline records, partition isolation, failed writes, ambiguous publication,
replacement during reads, corrupt/missing/cyclic/foreign references and bounded
planning. There is no batch-splitting assertion because no transactional batch is
used; instead tests verify successful round trips **beyond** native batch ceilings
without claiming atomicity.

Spanner uses the same wrapper and can be attempted with a correctly provisioned
Spanner configuration. Its configured native database ID must match
`referencing.database`. The published beta.1 Spanner adapter/schema behavior must
be validated in that environment; this sample does not claim live Spanner coverage.

### Local validation

Validated with Java 17 and DynamoDB Local:

- `mvn verify` with integration enabled: 23 unit cases and 8 live cases passed.
- Live logical sizes ranged from 535,621 to 6,291,831 bytes.
- `ALWAYS` on 130 embedded sections produced 519 fragments; write, read,
  replacement and isolated cleanup passed.
- The packaged standalone entry point also passed the 519-fragment scenario.
- Default `mvn test` passed without enabling integration tests.

Cosmos integration tests were not executed because emulator startup was canceled
at the Windows elevation prompt. Spanner integration tests were not executed.
Provider portability beyond the live DynamoDB validation remains to be confirmed.

## Source

- [AutoReferencingStore](src/main/java/com/multiclouddb/samples/referencing/AutoReferencingStore.java): bounded splitter and read-by-key wrapper
- [AutoReferencingSample](src/main/java/com/multiclouddb/samples/referencing/AutoReferencingSample.java): runnable demonstration
- [DocumentScenarios](src/main/java/com/multiclouddb/samples/referencing/DocumentScenarios.java): shared fixtures
- [Unit tests](src/test/java/com/multiclouddb/samples/referencing/AutoReferencingStoreTest.java)
- [Opt-in integration tests](src/test/java/com/multiclouddb/samples/referencing/AutoReferencingIntegrationTest.java)
