# Getting started

[Usage guide index](README.md) · [KEEP README](../README.md)

KEEP adds record mapping and database utilities to JetBrains Exposed. This example
uses PostgreSQL, a single table, and the same Kotlin/JVM versions as this checkout.

[![JetBrains logo](https://resources.jetbrains.com/storage/products/company/brand/logos/jetbrains.svg)](https://www.jetbrains.com/exposed/)

[![Kotlin logo](https://github.com/Kotlin.png?size=64)](https://kotlinlang.org/)

[![PostgreSQL logo](https://www.postgresql.org/media/img/about/press/elephant.png)](https://www.postgresql.org/)

## Add the dependencies

[![Gradle logo](https://github.com/gradle.png?size=64)](https://gradle.org/)

[![JitPack logo](https://github.com/jitpack.png?size=64)](https://jitpack.io/)

Add the following to an application's `build.gradle.kts`. JitPack hosts KEEP and
its TekFive dependencies; Maven Central supplies the other dependencies. The
application supplies the PostgreSQL JDBC driver at runtime.

```kotlin
plugins {
    kotlin("jvm") version "2.3.20"
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.TekFive:keep:v1.1.2")
    runtimeOnly("org.postgresql:postgresql:42.7.11")
}

kotlin {
    jvmToolchain(21)
}
```

This checkout references JFK `v1.0.10`. Applications normally receive the required
JSON library transitively through KEEP. See the [project build](../build.gradle.kts)
for the dependency versions of this checkout; an already published KEEP release
retains the dependencies with which it was built.

## Configure a database

Create a database and a role that can create tables and sequences in its schema.
For this example, supply its connection settings as environment variables:

```bash
export JDBC_URL='jdbc:postgresql://localhost:5432/keep_demo'
export JDBC_USER='keep_demo'
export JDBC_PASSWORD='replace-with-your-local-password'
```

The program below explicitly registers those values with ACK before calling
`DbConnection.startup()`. In an application that already configures ACK, use its
existing sources. See [connection configuration](database.md#database-connections)
for pooling and retry classification.

## Define a table and use it

Save this as `KeepExample.kt` in your application's source directory. The example
uses a fresh database: `DemoSchema.create()` installs its sequence and table and
is not a replacement for migrations on an existing database.

```kotlin
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import org.tekfive.keep.data.Data
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.db.DbConnection
import org.tekfive.keep.db.db
import org.tekfive.keep.schema.AppSchema

class SavedItem(val code: String, var label: String) : Data()

object SavedItems : DataTable<SavedItem>("saved_items") {
    val code = varchar("code", 64).uniqueIndex()
    val label = varchar("label", 255)
}

object DemoSchema : AppSchema() {
    override val tables = listOf(SavedItems)
}

fun main() {
    AckRegistry.addSource(MapSource(mapOf(
        "JDBC_URL" to requireNotNull(System.getenv("JDBC_URL")),
        "JDBC_USER" to requireNotNull(System.getenv("JDBC_USER")),
        "JDBC_PASSWORD" to requireNotNull(System.getenv("JDBC_PASSWORD")),
    )))
    DbConnection.startup()
    try {
        db { DemoSchema.create() }

        val itemId = db {
            SavedItems.create(SavedItem(code = "example", label = "First label")).id
        }

        db {
            val item = SavedItems.getById(itemId)
            item.label = "Updated label"
            SavedItems.save(item)
        }

        val savedLabel = db { SavedItems.getById(itemId).label }
        println(savedLabel)
    } finally {
        DbConnection.shutdown()
    }
}
```

`DataTable` supplies the ID column and maps the constructor properties. Mutable
properties participate in change tracking; the update writes the changed label.
`db` opens a transaction or joins the caller's existing transaction. Keep related
writes inside one block when they must commit together.

Use the application's normal Kotlin/JVM launch configuration to run the `main`
function. A second run against the same data would violate the unique `code`;
use a fresh database for this introductory example.

## Choose the next feature

- [Tables and singleton settings](tables.md): IDs, property mappings, relationships, and CRUD.
- [Schemas](schema.md) and [migrations](migrations.md): install and evolve a database.
- [Jobs](jobs.md): register workers, enqueue work, track progress, and cancel an attempt.
- [Encryption](encryption.md) and [auditing](auditing.md): protect values and record changes.
- [Development](development.md): test KEEP itself or work against sibling source checkouts.
