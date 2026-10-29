# Encrypted columns and keysets

[Usage guide index](README.md) · [KEEP README](../README.md)

[![Tink logo](https://github.com/tink-crypto.png?size=64)](https://developers.google.com/tink)

KEEP uses Tink AEAD primitives for encrypted database values. `DatabaseEncryptionProvider` is configured during application bootstrap and then encrypted column types use the active AEAD automatically.

```kotlin
DatabaseEncryptionProvider.configure(
    KeysetLoader.Config(
        mode = EncryptionKeysetMode.SEALED,
        file = Path.of("/var/lib/aideway/keyset.sealed"),
    )
)
DatabaseEncryptionProvider.ensureInitialized()
```

Encrypted column helpers include encrypted text, binary, enum, JSON object, JSON container, and object-list mappings.

```kotlin
class ApiToken(
    val name: String,
    var token: String,
) : Data()

object ApiTokensTable : DataTable<ApiToken>("api_tokens") {
    val name = name(unique = true)
    val token = encryptedText("token")
}
```

Keyset loading supports three modes:

- `PLAINTEXT`: cleartext Tink JSON keyset on disk, intended for local development.
- `SEALED`: sealed keyset file unwrapped through Clevis/TPM, intended for production.
- `RECOVERY`: passphrase-based recovery file using Argon2id and authenticated encryption.

Recovery-file parsing includes size and Argon2 parameter limits to avoid unbounded local resource use from malformed files.

## Next steps

See [auditing](auditing.md) for encrypted snapshots and secret-field exclusions. The implementation is in [DatabaseEncryptionProvider](../src/main/kotlin/org/tekfive/keep/encryption/DatabaseEncryptionProvider.kt) and [KeysetLoader](../src/main/kotlin/org/tekfive/keep/encryption/KeysetLoader.kt).
