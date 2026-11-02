package space.linuxct.nothing.icongen

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.InetAddresses
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.AtomicFile
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.PairingConnectionCtx
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.Date
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** Only this device's numeric IP addresses are accepted; no DNS or remote-device ADB. */
internal data class WirelessAdbEndpoint(val address: InetAddress, val port: Int) {
    companion object {
        fun parse(host: String, port: String): WirelessAdbEndpoint {
            val numericHost = host.trim().removeSurrounding("[", "]")
            require(InetAddresses.isNumericAddress(numericHost)) {
                "Enter this device's numeric IP address, not a hostname."
            }
            val address = InetAddresses.parseNumericAddress(numericHost)
            val number = port.trim().toIntOrNull()
            require(number != null && number in 1..65535) { "Enter a port from 1 to 65535." }
            val local = address.isLoopbackAddress || NetworkInterface.getNetworkInterfaces()
                .toList().any { it.isUp && it.inetAddresses.toList().contains(address) }
            require(local && !address.isAnyLocalAddress && !address.isMulticastAddress) {
                "Only loopback or an IP currently assigned to this device is allowed."
            }
            return WirelessAdbEndpoint(address, number)
        }
    }
}

internal data class WirelessGrantStatus(
    val secureSettings: Boolean,
    val writeSettings: Boolean,
    val writeSettingsMode: Int,
) {
    val complete: Boolean get() = secureSettings && writeSettings &&
        writeSettingsMode == AppOpsManager.MODE_ALLOWED

    fun describe(): String = "WRITE_SECURE_SETTINGS: ${if (secureSettings) "granted" else "not granted"}\n" +
        "WRITE_SETTINGS: ${if (writeSettings) "allowed" else "not allowed"} " +
        "(AppOps ${if (writeSettingsMode == AppOpsManager.MODE_ALLOWED) "allow" else writeSettingsMode.toString()})"

    companion object {
        fun read(context: Context): WirelessGrantStatus {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            return WirelessGrantStatus(
                context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED,
                Settings.System.canWrite(context),
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_WRITE_SETTINGS, Process.myUid(), context.packageName,
                ),
            )
        }
    }
}

/** A single short-lived operation; cancellation closes sockets even during library TLS/SPAKE2 calls. */
internal class WirelessAdbClient(private val context: Context) {
    @Volatile private var cancelled: String? = null
    @Volatile private var relay: LocalAdbRelay? = null
    @Volatile private var worker: Thread? = null

    fun cancel(reason: String) {
        cancelled = reason
        relay?.close()
        worker?.interrupt()
    }

    private fun checkCancelled() {
        cancelled?.let { throw CancellationException(it) }
    }

    fun failureMessage(error: Throwable): String = cancelled ?: when (error) {
        is UnsatisfiedLinkError -> "Wireless ADB native libraries could not load on this device. ${error.message.orEmpty()}"
        else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    fun pair(endpoint: WirelessAdbEndpoint, code: ByteArray) {
        require(code.size == 6 && code.all { it.toInt() in 48..57 }) {
            "Enter the six-digit pairing code."
        }
        run(endpoint, requireAdbTls = false) { port ->
            val identity = AdbIdentity.load(context)
            val pairing = PairingConnectionCtx(
                "127.0.0.1", port, code, identity.privateKey, identity.certificate, "IconGen",
            )
            try {
                pairing.start()
                checkCancelled()
            } finally {
                // Upstream close() assumes TLS and SPAKE2 initialization succeeded. Preserve the real failure.
                runCatching { pairing.close() }
            }
        }
    }

    fun grant(endpoint: WirelessAdbEndpoint): WirelessGrantStatus = run(endpoint, requireAdbTls = true) { port ->
        check(context.packageName == PACKAGE) { "Unexpected application package; grants were not executed." }
        val identity = AdbIdentity.load(context)
        val connection = AdbConnection.Builder("127.0.0.1", port)
            .setApi(Build.VERSION.SDK_INT)
            .setPrivateKey(identity.privateKey)
            .setCertificate(identity.certificate)
            .setDeviceName("IconGen")
            .build()
        try {
            check(connection.connect(15, TimeUnit.SECONDS, true)) {
                "ADB connection timed out. Check the main Wireless debugging port, not the pairing port."
            }
            checkCancelled()
            execute(connection, "pm grant $PACKAGE android.permission.WRITE_SECURE_SETTINGS")
            checkCancelled()
            execute(connection, "appops set $PACKAGE WRITE_SETTINGS allow")
            checkCancelled()
            val status = WirelessGrantStatus.read(context)
            check(status.complete) {
                "ADB commands finished, but Android did not confirm both grants.\n${status.describe()}"
            }
            status
        } finally {
            // Close the relay first so upstream's connection-thread join cannot wait on network I/O.
            relay?.close()
            connection.close()
        }
    }

    private fun execute(connection: AdbConnection, command: String) {
        checkCancelled()
        connection.open("shell:$command; result=\$?; printf '\\nICONGEN_EXIT=%s\\n' \"\$result\"").use { stream ->
            val output = StringBuilder()
            val bytes = ByteArray(1024)
            val input = stream.openInputStream()
            while (true) {
                checkCancelled()
                val count = try {
                    input.read(bytes)
                } catch (error: IOException) {
                    // This library reports normal stream closure as IOException, not always EOF.
                    if (stream.isClosed) break else throw error
                }
                if (count < 0) break
                require(output.length + count <= 16_384) { "Unexpectedly large ADB command response." }
                output.append(String(bytes, 0, count, Charsets.UTF_8))
                // Do not await EOF: upstream can leave readers waiting after a peer's pending close.
                if (EXIT_MARKER.containsMatchIn(output)) break
            }
            val text = output.toString().trim()
            val result = EXIT_MARKER.find(text + "\n")
                ?: throw IOException("ADB command ended without an exit status. ${text.take(1000)}")
            if (result.groupValues[1] != "0") {
                throw IOException("ADB grant was rejected (exit ${result.groupValues[1]}). " +
                    text.substring(0, result.range.first).trim().take(1000))
            }
        }
    }

    private fun <T> run(endpoint: WirelessAdbEndpoint, requireAdbTls: Boolean, action: (Int) -> T): T {
        worker = Thread.currentThread()
        checkCancelled()
        // Recheck immediately before opening sockets in case a network changed after form validation.
        val current = WirelessAdbEndpoint.parse(endpoint.address.hostAddress!!, endpoint.port.toString())
        val transport = LocalAdbRelay(current, requireAdbTls)
        relay = transport
        try {
            checkCancelled()
            val result = action(transport.port)
            checkCancelled()
            return result
        } catch (error: Exception) {
            checkCancelled()
            throw transport.failure ?: error
        } finally {
            transport.close()
            relay = null
            worker = null
        }
    }

    companion object {
        private const val PACKAGE = "space.linuxct.nothing.icongen"
        private val EXIT_MARKER = Regex("(?:^|\\n)ICONGEN_EXIT=(\\d+)\\r?\\n")
    }
}

/**
 * libadb 3.1.1 creates its own sockets without connect/read timeouts or a cancel handle during pairing.
 * A one-use loopback relay owns both sockets, bounding I/O and allowing cancellation without reflection.
 * For ADB connections it also requires the daemon's first packet to be STLS, rejecting legacy TCP ADB.
 * Pairing TLS, TLS exporter binding, and SPAKE2 are implemented entirely by libadb/Conscrypt.
 */
private class LocalAdbRelay(endpoint: WirelessAdbEndpoint, private val requireAdbTls: Boolean) : Closeable {
    private val lock = Any()
    private var closed = false
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private var client: Socket? = null
    private var remote: Socket? = null
    @Volatile var failure: IOException? = null
        private set
    val port: Int get() = server.localPort

    init {
        Thread({
            try {
                server.soTimeout = 15_000
                val accepted = server.accept()
                synchronized(lock) {
                    if (closed) { accepted.close(); return@Thread }
                    client = accepted
                }
                server.close()
                val destination = Socket()
                synchronized(lock) {
                    if (closed) { destination.close(); return@Thread }
                    remote = destination
                }
                destination.connect(InetSocketAddress(endpoint.address, endpoint.port), 10_000)
                destination.soTimeout = 20_000
                accepted.soTimeout = 20_000
                destination.tcpNoDelay = true
                accepted.tcpNoDelay = true
                Thread({ pump(accepted, destination, false) }, "IconGen-adb-send").start()
                pump(destination, accepted, requireAdbTls)
            } catch (error: IOException) {
                fail(error)
            }
        }, "IconGen-adb-relay").start()
    }

    private fun pump(from: Socket, to: Socket, checkTls: Boolean) {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            if (checkTls) {
                val header = ByteArray(24)
                DataInputStream(input).readFully(header)
                if (header[0] != 83.toByte() || header[1] != 84.toByte() ||
                    header[2] != 76.toByte() || header[3] != 83.toByte()) {
                    throw IOException("Not a Wireless debugging TLS port")
                }
                output.write(header)
                output.flush()
            }
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (error: IOException) {
            fail(error)
        } finally {
            close()
        }
    }

    private fun fail(error: IOException) {
        synchronized(lock) {
            if (!closed && failure == null) failure = error
        }
        close()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { server.close() }
            runCatching { client?.close() }
            runCatching { remote?.close() }
        }
    }
}

private data class AdbIdentity(val privateKey: PrivateKey, val certificate: Certificate) {
    companion object {
        private val lock = Any()

        fun load(context: Context): AdbIdentity = synchronized(lock) {
            // The app-private, no-backup directory provides confidentiality; this password is only PKCS12 framing.
            val password = "IconGen wireless ADB".toCharArray()
            val file = AtomicFile(File(context.noBackupFilesDir, "wireless-adb-identity.p12"))
            val store = KeyStore.getInstance("PKCS12")
            try {
                if (file.baseFile.exists()) {
                    file.openRead().use { store.load(it, password) }
                } else {
                    store.load(null, password)
                    val key = KeyPairGenerator.getInstance("RSA").apply {
                        initialize(2048, SecureRandom())
                    }.generateKeyPair()
                    val now = System.currentTimeMillis()
                    val subject = X500Name("CN=IconGen Wireless ADB")
                    val builder = JcaX509v3CertificateBuilder(
                        subject, BigInteger(128, SecureRandom()).setBit(127),
                        Date(now - 86_400_000L), Date(now + 10L * 365 * 86_400_000L), subject, key.public,
                    )
                    val signer = JcaContentSignerBuilder("SHA256withRSA").build(key.private)
                    val certificate = JcaX509CertificateConverter()
                        .getCertificate(builder.build(signer))
                    store.setKeyEntry("adb", key.private, password, arrayOf(certificate))
                    val output = file.startWrite()
                    try {
                        store.store(output, password)
                        file.finishWrite(output)
                    } catch (error: Throwable) {
                        file.failWrite(output)
                        throw error
                    }
                }
                AdbIdentity(store.getKey("adb", password) as PrivateKey, store.getCertificate("adb"))
            } finally {
                password.fill('\u0000')
            }
        }
    }
}
