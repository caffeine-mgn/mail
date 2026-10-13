# mail

A from-scratch, dependency-light **Kotlin Multiplatform mail stack**: MIME plus
SMTP, Submission, POP3 and IMAP4rev1 — each with a protocol model (no I/O), a
coroutine-friendly client, and an in-process server — and a composed all-in-one
mail server that speaks all four protocols over one shared mailbox store.

Everything is written in `commonMain` and built for several KMP targets.

## What's inside

| Area | Coverage |
|------|----------|
| **MIME** | RFC 2045–2049 (encodings, multipart), RFC 2231 (parameter continuation/encoding), RFC 5322 (addresses, headers) |
| **SMTP** | RFC 5321 (EHLO/HELO fallback, MAIL/RCPT/DATA, dot-stuffing, RSET/NOOP/VRFY), RFC 4954 AUTH `PLAIN`/`LOGIN`, RFC 3207 STARTTLS |
| **Submission** | RFC 6409 (separate listener, mandatory auth), SASL `SCRAM-SHA-256` and `PLAIN` |
| **POP3** | RFC 1939 (USER/PASS, STAT/LIST/UIDL/RETR/DELE/TOP/RSET/NOOP/QUIT), RFC 2449 CAPA, RFC 2595 STLS |
| **IMAP4rev1** | RFC 3501 (LOGIN, LIST/LSUB, SELECT/EXAMINE, STATUS, FETCH with real `{n}` literals, STORE, COPY, SEARCH, APPEND, EXPUNGE, CLOSE, IDLE), AUTHENTICATE `PLAIN`/`LOGIN` |
| **IMAP extensions** | RFC 4551 CONDSTORE, RFC 5162 QRESYNC, RFC 5161 ENABLE, RFC 5256 SORT & THREAD, RFC 5465 NOTIFY, RFC 2595 STARTTLS |
| **TLS** | Client and server STARTTLS/STLS for SMTP/IMAP/POP3 via [`pw.binom:tls`](../tls) (JSSE on JVM, OpenSSL on native) |

Messages are parsed/assembled by the `mime` module, so `FETCH ENVELOPE`,
`BODYSTRUCTURE`, `BODY[HEADER]` and the like are derived from the actual bytes
rather than stored metadata.

## Modules

```
mime                     MIME parsing / assembling

smtp-protocol            commands, replies, parser, EHLO capabilities   (no I/O)
smtp-client              SmtpClient        (ktor-network)
smtp-server              SmtpServer / SmtpSession / SmtpMailbox

submission-protocol      submission commands
submission-client        SubmissionClient  (SMTP + STARTTLS + SASL)
submission-server        SubmissionServer  (SMTP with requireAuth = true)

pop3-protocol            commands, replies, CAPA
pop3-client              Pop3Client
pop3-server              Pop3Server / Pop3Session / Pop3Mailbox

imap-protocol            commands, responses, parser, response codes
imap-client              ImapClient
imap-server              ImapServer / ImapSession / ImapMailbox

mail-server              composed SMTP + Submission + IMAP + POP3 over one MailStore
mail-testkit             Testcontainers / GreenMail test infrastructure (JVM only)
```

Every protocol is split into `-protocol` (pure model, no I/O), `-client`, and
`-server`, so you can take only the pieces you need.

`mail-server` is a runnable example: one `MailStore` behind
`SmtpDeliveryRouter`, `ImapMailboxAdapter` and `Pop3MailboxAdapter`; a message
accepted over SMTP is immediately visible over IMAP and POP3.

## Targets

`jvm`, `linuxX64`, `linuxArm64`, `mingwX64`, `macosArm64`, `iosArm64`,
`iosSimulatorArm64`, `iosX64`.

Only host-buildable targets actually execute; the others are configured and
skipped on the current host.

## Build & test

```bash
./gradlew jvmTest          # unit tests on JVM + Testcontainers integration tests
./gradlew linuxX64Test     # pure commonTest suite on Kotlin/Native
./gradlew jvmJar           # assemble all modules
```

> On machines where the global `~/.gradle/init.gradle.kts` injects a private
> plugin/repository, run Gradle with an isolated home:
> `GRADLE_USER_HOME=/tmp/opencode/mail-gradle ./gradlew …`.
> Native linking on a memory-constrained host may need `--max-workers=1`.

### Integration tests

Two tiers:

1. **`commonTest`** — pure protocol/dialog tests, no external services; they run
   on every target (JVM and native).
2. **`jvmTest` + Testcontainers** — the `-client` modules drive common code
   against a real `greenmail/standalone` container (SMTP + Submission + IMAP +
   POP3). The container is started once per test JVM (see `:mail-testkit`);
   `DOCKER_HOST` must be available to Gradle.

## TLS

Server-side TLS needs a session library that `ktor-network-tls` does not
provide (ktor only covers the client handshake, and only on the JVM), so the
sibling `../tls` project is wired in as a composite build

```kotlin
includeBuild("../tls")
```

and consumed as `pw.binom:tls:0.1.0-SNAPSHOT`. Servers take an optional
`tls: SSLContext? = null`; when set they advertise `STARTTLS`/`STLS` and upgrade
in place. Clients expose `startTls(serverName, trustAll)` / `stls(...)`.

## Running your own server

```bash
./gradlew :mail-server:run
```

| Env | Default | Meaning |
|-----|---------|---------|
| `MAIL_HOST` | `0.0.0.0` | bind address |
| `MAIL_HOSTNAME` | `mail.local` | EHLO/greeting hostname |
| `MAIL_SMTP_PORT` | `25` | SMTP (MTA) |
| `MAIL_SUBMISSION_PORT` | `587` | Submission (auth required) |
| `MAIL_IMAP_PORT` | `143` | IMAP |
| `MAIL_POP3_PORT` | `110` | POP3 |
| `MAIL_USER` / `MAIL_PASS` | `user` / `secret` | demo account |
| `MAIL_TLS_CERT` / `MAIL_TLS_KEY` | — | DER cert chain + PKCS#8 key; enables STARTTLS/STLS |

## Design notes

- **In-memory mailboxes.** The bundled server keeps mail in memory only; disk
  persistence is intentionally out of scope for now.
- **No silent stubs.** Unfinished protocol surface is marked
  `TODO(protocol): <rfc> — <acceptance>` with a matching `@Ignore`d test, rather
  than shipped as a no-op.
