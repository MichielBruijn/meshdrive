package nu.bruijn.rcvehicle.yggdrasil

/**
 * A geographically-spread snapshot of community-run public peers from
 * github.com/yggdrasil-network/public-peers (taken 2026-09-16), used as the
 * candidate pool when the user hasn't configured any peers themselves (see
 * ModePrefs.yggdrasilPeerCandidates) AND as the fallback when
 * YggdrasilPeerListCache's live refresh from that same repo hasn't run yet or
 * fails (no internet, GitHub down, etc.) - see its doc for the actual
 * candidate pool callers get, which is this list unioned with a live-fetched
 * one, refreshed monthly.
 *
 * quic:// and ws:// peers from that repo are deliberately left out: PeerMonitor
 * only ever selects a candidate PeerLatencyProbe could actually measure, and
 * the probe only knows how to time a raw TCP connect (see its doc) - a quic://
 * entry here would just always read as "unreachable" and never get picked.
 *
 * Staleness is self-healing rather than something that needs active
 * maintenance: PeerMonitor probes every candidate on its own cadence and only
 * ever connects through one that actually answered, so an individual peer
 * going offline over time just quietly drops out of contention rather than
 * breaking anything - as long as at least one entry below is still alive.
 */
object YggdrasilBootstrapPeers {
    val CANDIDATES: List<String> = listOf(
        // Netherlands
        "tcp://vpn.itrus.su:7991",
        "tls://vpn.itrus.su:7992",
        "tcp://cirno.nadeko.net:44441",
        "tcp://146.103.111.53:65535",
        // Germany
        "tcp://ygg.mkg20001.io:80",
        "tls://ygg.mkg20001.io:443",
        "tcp://ygg1.mk16.de:1337?key=0000000087ee9949eeab56bd430ee8f324cad55abf3993ed9b9be63ce693e18a",
        // France
        "tcp://51.15.204.214:12345",
        "tls://51.15.204.214:54321",
        // United Kingdom
        "tls://london.sabretruth.org:18472",
        "tcp://london.sabretruth.org:18473",
        // Sweden
        "tcp://77.91.84.76:65535",
        // Poland
        "tcp://waw01.yggdrasil.hosted-by.skhron.eu:8883?key=030602cee88a761c68f5f14e1dad430f25238a703b69dc382321a38f833035b0",
        // Spain
        "tcp://rendezvous.anton.molyboha.me:50421",
        // United States
        "tcp://165.227.17.198:9002",
        "tls://ca.us.ygg.informatics.coop:443?key=0000000023fc6ffb62afb79448aefb0943df2e4472cf08bd489f5407378300a2",
        // Canada
        "tcp://ygg3.mk16.de:1337?key=000003acdaf2a60e8de2f63c3e63b7e911d02380934f09ee5c83acb758f470c1",
        // Brazil
        "tcp://Yggdrasil.UnderEu.Net:37001",
        "tls://thinkmedia.freeddns.org:9002",
        // Japan
        "tls://yg-tyo.magicum.net:32333",
        "tcp://yg-tyo.magicum.net:32334",
        "tls://astrra.space:55535",
        // India
        "tls://ins.8px.sk:4321",
        // Australia
        "tcp://yg-syd.magicum.net:23701",
        "tls://syd.joel.net.au:8443",
        // South Africa
        "tcp://r1.worcester.yggdrasil.novasredes.network:2000",
        "tls://r1.worcester.yggdrasil.novasredes.network:2001",
    )
}
