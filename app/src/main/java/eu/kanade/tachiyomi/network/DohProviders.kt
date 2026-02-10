package eu.kanade.tachiyomi.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Based on https://github.com/square/okhttp/blob/master/okhttp-dnsoverhttps/src/test/java/okhttp3/dnsoverhttps/DohProviders.kt
 * and https://github.com/curl/curl/wiki/DNS-over-HTTPS
 *
 * DoH Error Handling:
 * - Aggressive timeouts (5s connect, 5s read, 8s total) to fail fast
 * - Automatic retry on connection failures
 * - DohErrorInterceptor catches 504 gateway timeouts and other DoH errors
 * - Falls back to system DNS automatically if DoH fails
 * - DoH errors are logged locally but not sent to error tracking
 */

const val PREF_DOH_CLOUDFLARE = 1
const val PREF_DOH_GOOGLE = 2
const val PREF_DOH_ADGUARD = 3
const val PREF_DOH_QUAD9 = 4
const val PREF_DOH_ALIDNS = 5
const val PREF_DOH_DNSPOD = 6
const val PREF_DOH_360 = 7
const val PREF_DOH_QUAD101 = 8
const val PREF_DOH_MULLVAD = 9
const val PREF_DOH_CONTROLD = 10
const val PREF_DOH_NJALLA = 11
const val PREF_DOH_SHECAN = 12
const val PREF_DOH_LIBREDNS = 13
const val PREF_DOH_OPENDNS = 14
const val PREF_DOH_CLEANBROWSING = 15
const val PREF_DOH_NEXTDNS = 16

/**
 * Helper function to create a DoH client with proper timeout configuration
 * to prevent gateway timeout errors (504) and improve reliability
 */
private fun OkHttpClient.Builder.buildDohClient(): OkHttpClient {
    return build()
        .newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)  // Reduced from 10s
        .readTimeout(5, TimeUnit.SECONDS)      // Reduced from 10s
        .callTimeout(8, TimeUnit.SECONDS)      // Reduced from 15s
        .retryOnConnectionFailure(true)        // Enable automatic retry
        .build()
}

fun OkHttpClient.Builder.dohCloudflare() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("1.0.0.1"),
            InetAddress.getByName("2606:4700:4700::1111"),
            InetAddress.getByName("2606:4700:4700::1001"),
        )
        .build(),
)

fun OkHttpClient.Builder.dohGoogle() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://dns.google/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("8.8.4.4"),
            InetAddress.getByName("8.8.8.8"),
            InetAddress.getByName("2001:4860:4860::8888"),
            InetAddress.getByName("2001:4860:4860::8844"),
        )
        .build(),
)

/**
 * AdGuard DNS - Unfiltered
 * Non-filtering DNS for unrestricted access
 * Source: https://adguard-dns.io/
 */
fun OkHttpClient.Builder.dohAdGuard() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://unfiltered.adguard-dns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("94.140.14.140"),
            InetAddress.getByName("94.140.14.141"),
            InetAddress.getByName("2a10:50c0::1:ff"),
            InetAddress.getByName("2a10:50c0::2:ff"),
        )
        .build(),
)

/**
 * Quad9
 * Source: https://www.quad9.net/
 */
fun OkHttpClient.Builder.dohQuad9() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://dns.quad9.net/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("9.9.9.9"),
            InetAddress.getByName("149.112.112.112"),
            InetAddress.getByName("2620:fe::fe"),
            InetAddress.getByName("2620:fe::9"),
        )
        .build(),
)

/**
 * Alibaba Cloud DNS
 * Chinese public DNS service
 * Source: https://www.alidns.com/
 */
fun OkHttpClient.Builder.dohAliDNS() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://dns.alidns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("223.5.5.5"),
            InetAddress.getByName("223.6.6.6"),
            InetAddress.getByName("2400:3200::1"),
            InetAddress.getByName("2400:3200:baba::1"),
        )
        .build(),
)

/**
 * DNSPod (Tencent)
 * Chinese public DNS service
 * Source: https://www.dnspod.cn/
 */
fun OkHttpClient.Builder.dohDNSPod() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://doh.pub/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("1.12.12.12"),
            InetAddress.getByName("120.53.53.53"),
        )
        .build(),
)

/**
 * 360 Secure DNS
 * Chinese public DNS service
 * Source: https://sdns.360.net/
 */
fun OkHttpClient.Builder.doh360() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://doh.360.cn/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("101.226.4.6"),
            InetAddress.getByName("218.30.118.6"),
            InetAddress.getByName("123.125.81.6"),
            InetAddress.getByName("140.207.198.6"),
        )
        .build(),
)

/**
 * Quad101 (TWNIC)
 * Taiwan Network Information Center public DNS
 * Source: https://101.101.101.101/
 */
fun OkHttpClient.Builder.dohQuad101() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://dns.twnic.tw/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("101.101.101.101"),
            InetAddress.getByName("2001:de4::101"),
            InetAddress.getByName("2001:de4::102"),
        )
        .build(),
)

/**
 * Mullvad DNS
 * No ad-blocking, no logging
 * Source: https://mullvad.net/en/help/dns-over-https-and-dns-over-tls/
 */
fun OkHttpClient.Builder.dohMullvad() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://doh.mullvad.net/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("194.242.2.2"),
            InetAddress.getByName("193.19.108.2"),
            InetAddress.getByName("2a07:e340::2"),
        )
        .build(),
)

/**
 * Control D
 * Unfiltered option - no blocking
 * Source: https://controld.com/free-dns
 */
fun OkHttpClient.Builder.dohControlD() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://freedns.controld.com/p0".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("76.76.2.0"),
            InetAddress.getByName("76.76.10.0"),
            InetAddress.getByName("2606:1a40::"),
            InetAddress.getByName("2606:1a40:1::"),
        )
        .build(),
)

/**
 * Njalla DNS
 * Privacy-focused, no logging, uncensored
 * Source: https://dns.njal.la/
 */
fun OkHttpClient.Builder.dohNajalla() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://dns.njal.la/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("95.215.19.53"),
            InetAddress.getByName("2001:67c:2354:2::53"),
        )
        .build(),
)

/**
 * Shecan DNS
 * Iranian DNS service for bypassing restrictions
 * Source: https://shecan.ir/
 */
fun OkHttpClient.Builder.dohShecan() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://free.shecan.ir/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("178.22.122.100"),
            InetAddress.getByName("185.51.200.2"),
        )
        .build(),
)

/**
 * LibreDNS
 * No logs, no censorship
 * Source: https://libredns.gr/
 */
fun OkHttpClient.Builder.dohLibreDNS() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://doh.libredns.gr/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("116.202.176.26"),
            InetAddress.getByName("2a01:4f8:1c0c:8233::1"),
        )
        .build()
)

/**
 * OpenDNS (Cisco)
 * Source: https://www.opendns.com/
 */
fun OkHttpClient.Builder.dohOpenDNS() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://doh.opendns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("208.67.222.222"),
            InetAddress.getByName("208.67.220.220"),
            InetAddress.getByName("2620:119:35::35"),
            InetAddress.getByName("2620:119:53::53"),
        )
        .build()
)

/**
 * CleanBrowsing
 * Family filter - blocks adult content
 * Source: https://cleanbrowsing.org/
 */
fun OkHttpClient.Builder.dohCleanBrowsing() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://doh.cleanbrowsing.org/doh/family-filter/".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("185.228.168.168"),
            InetAddress.getByName("185.228.169.168"),
            InetAddress.getByName("2a0d:2a00:1::"),
            InetAddress.getByName("2a0d:2a00:2::"),
        )
        .build()
)

/**
 * NextDNS
 * Unconfigured endpoint (no filtering)
 * Source: https://nextdns.io/
 */
fun OkHttpClient.Builder.dohNextDNS() = dns(
    DnsOverHttps.Builder().client(buildDohClient())
        .url("https://dns.nextdns.io/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("45.90.28.0"),
            InetAddress.getByName("45.90.30.0"),
            InetAddress.getByName("2a07:a8c0::"),
            InetAddress.getByName("2a07:a8c1::"),
        )
        .build()
)
