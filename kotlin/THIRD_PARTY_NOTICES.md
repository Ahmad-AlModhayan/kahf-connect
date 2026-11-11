# Third-party notices: Kahf Connect Kotlin SDK

The Kotlin SDK (`kotlin/`) is Apache-2.0, like the rest of this repository. It links these libraries; every one is under a permissive licence with no source obligation. Apache-2.0 asks that its notice files travel with a binary that bundles the library.

| Library | Version | Licence | Used for |
|---|---|---|---|
| [cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin) (`dev.whyoleg.cryptography:cryptography-core`, `-provider-jdk-bc`, `-provider-cryptokit`) | 0.6.0 | Apache-2.0 | Ed25519 catalog signatures (SPEC §8) |
| [Bouncy Castle](https://www.bouncycastle.org/) (`org.bouncycastle:bcprov-jdk18on`, pulled in by `cryptography-provider-jdk-bc`) | 1.83 | Bouncy Castle Licence (MIT-style: "an adaptation of the MIT X11 License") | Ed25519 on Android and the JVM |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (`kotlinx-serialization-json`) | 1.12.0 | Apache-2.0 | JSON |
| [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime) | 0.8.0 | Apache-2.0 | Times and time zones |
| [kotlinx-io](https://github.com/Kotlin/kotlinx-io) (pulled in by cryptography-kotlin) | transitive | Apache-2.0 | Byte buffers |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) (`kotlinx-coroutines-core`; `-test` for tests) | 1.11.0 | Apache-2.0 | Timeouts and parallel connector calls; running suspend tests |
| [Ktor](https://github.com/ktor/ktor) (`ktor-client-core`, `-okhttp`, `-darwin`; `-mock` for tests) | 3.6.1 | Apache-2.0 | The MCP client, calendar feeds, model listing and the BYO provider client |
| [OkHttp](https://github.com/square/okhttp) and [Okio](https://github.com/square/okio) (pulled in by `ktor-client-okhttp`) | 5.5.1 / 3.18.2 | Apache-2.0 | HTTP on Android and the JVM |
| [SLF4J API](https://www.slf4j.org/) (`org.slf4j:slf4j-api`, pulled in by Ktor on the JVM) | 2.0.19 | MIT | Ktor's logging facade |

Licences were read from each library's published POM (and, for cryptography-kotlin, its GitHub repository) on 2026-11-09.
