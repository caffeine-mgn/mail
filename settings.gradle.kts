pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mail"

includeBuild("build-logic")

// TLS session library (sibling project). Provides server-side TLS (JSSE on JVM,
// OpenSSL on native) — ktor-network-tls only covers the client handshake on JVM.
includeBuild("../tls")

include(
    // MIME core
    ":mime",

    // SMTP (MTA, port 25)
    ":smtp-protocol",
    ":smtp-client",
    ":smtp-server",

    // Submission (RFC 6409, port 587)
    ":submission-protocol",
    ":submission-client",
    ":submission-server",

    // POP3 (RFC 1939)
    ":pop3-protocol",
    ":pop3-client",
    ":pop3-server",

    // IMAP4rev1 (RFC 3501)
    ":imap-protocol",
    ":imap-client",
    ":imap-server",

    // Composed in-memory mail server (SMTP + Submission + IMAP + POP3)
    ":mail-server",

    // Test infrastructure (Testcontainers / GreenMail) — JVM only
    ":mail-testkit",
)
