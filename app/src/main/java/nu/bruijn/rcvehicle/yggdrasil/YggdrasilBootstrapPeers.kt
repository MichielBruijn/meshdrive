package nu.bruijn.rcvehicle.yggdrasil

/**
 * A small, geographically-spread snapshot of community-run public peers from
 * github.com/yggdrasil-network/public-peers (taken 2026-09-15), used as the
 * candidate pool when the user hasn't configured any peers themselves (see
 * ModePrefs.yggdrasilPeers). Per direct request - this app previously shipped
 * with none at all, on the theory that a stale hardcoded peer is worse than
 * "not connected yet," but that leaves a fresh install unable to connect to
 * anything until someone manually types a peer in.
 *
 * quic:// and ws:// peers from that list are deliberately left out: PeerMonitor
 * only ever selects a candidate PeerLatencyProbe could actually measure, and
 * the probe only knows how to time a raw TCP connect (see its doc) - a quic://
 * entry here would just always read as "unreachable" and never get picked.
 *
 * Staleness is self-healing rather than something that needs active
 * maintenance: PeerMonitor probes every candidate on its own cadence and only
 * ever connects through one that actually answered, so an individual peer
 * going offline over time just quietly drops out of contention rather than
 * breaking anything - as long as at least one entry below is still alive.
 * Worth refreshing this list occasionally regardless, from the same repo.
 */
object YggdrasilBootstrapPeers {
    val CANDIDATES: List<String> = listOf(
        // Netherlands
        "tcp://vpn.itrus.su:7991",
        "tls://vpn.itrus.su:7992",
        "tcp://cirno.nadeko.net:44441",
        // Germany
        "tcp://ygg.mkg20001.io:80",
        "tls://ygg.mkg20001.io:443",
        // France
        "tcp://51.15.204.214:12345",
        // United States
        "tcp://165.227.17.198:9002",
        "tls://ca.us.ygg.informatics.coop:443?key=0000000023fc6ffb62afb79448aefb0943df2e4472cf08bd489f5407378300a2",
        // Japan
        "tls://yg-tyo.magicum.net:32333",
        "tcp://yg-tyo.magicum.net:32334",
        // Australia
        "tcp://yg-syd.magicum.net:23701",
        "tls://syd.joel.net.au:8443",
        // Brazil
        "tls://thinkmedia.freeddns.org:9002",
        // South Africa
        "tcp://r1.worcester.yggdrasil.novasredes.network:2000",
        "tls://r1.worcester.yggdrasil.novasredes.network:2001",
    )
}
