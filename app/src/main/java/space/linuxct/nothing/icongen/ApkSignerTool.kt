package space.linuxct.nothing.icongen

import android.content.Context
import android.util.Log
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Signs the generated icon-pack APK on-device.
 *
 * The signing key is generated once and stored in the app's private files dir, so every
 * subsequent icon pack is signed with the same certificate and can be installed as an
 * update of the previous one. apksig is used directly (the same library AGP uses) so the
 * APK is v2/v3 signed without needing an external apksigner binary.
 */
object ApkSignerTool {

    private const val TAG = "ApkSignerTool"
    private const val KEYSTORE = "iconpack-keystore.jks"
    private const val ALIAS = "iconpack"
    private const val PASSWORD = "iconpack"
    private const val KEY_ALGORITHM = "RSA"
    private const val KEY_SIZE = 2048
    private const val VALIDITY_YEARS = 30L

    /** Returns [apk] signed in place; the unsigned input is replaced. */
    fun signApk(context: Context, apk: File): File {
        val keyStoreFile = File(context.filesDir, KEYSTORE)
        val keyStore = loadOrCreateKeyStore(keyStoreFile)

        val privateKey = keyStore.getKey(ALIAS, PASSWORD.toCharArray()) as java.security.PrivateKey
        val certificate = keyStore.getCertificate(ALIAS) as X509Certificate

        val unsigned = File(apk.parentFile, "${apk.name}.unsigned")
        if (unsigned.exists()) unsigned.delete()
        if (!apk.renameTo(unsigned)) {
            throw RuntimeException("Cannot move unsigned APK aside: ${unsigned.absolutePath}")
        }

        try {
            ApkSigner.SignerConfig.Builder(
                ALIAS,
                privateKey,
                listOf(certificate),
            ).build().let { signerConfig ->
                ApkSigner.Builder(listOf(signerConfig))
                    .setInputApk(unsigned)
                    .setOutputApk(apk)
                    .setV1SigningEnabled(false)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .build()
                    .sign()
            }
        } catch (t: Throwable) {
            unsigned.renameTo(apk)
            throw RuntimeException("APK signing failed: ${t.stackTraceToString()}", t)
        }

        if (!apk.exists()) {
            throw RuntimeException("Signed APK was not produced at ${apk.absolutePath}")
        }
        unsigned.delete()
        Log.d(TAG, "Signed ${apk.absolutePath} with on-device key (${apk.length()} bytes)")
        return apk
    }

    private fun loadOrCreateKeyStore(file: File): KeyStore {
        if (file.exists()) {
            return KeyStore.getInstance(KEYSTORE_TYPE).apply {
                file.inputStream().use { load(it, PASSWORD.toCharArray()) }
            }
        }

        Log.d(TAG, "Generating signing key at ${file.absolutePath}")
        val keyPair = KeyPairGenerator.getInstance(KEY_ALGORITHM).apply {
            initialize(KEY_SIZE, SecureRandom())
        }.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + VALIDITY_YEARS * 365L * 24 * 60 * 60 * 1000)
        val subject = X500Name("CN=Nothing Translucent Icon Pack, O=IconGen, C=US")

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()).abs(),
            notBefore,
            notAfter,
            subject,
            keyPair.public,
        )
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(certBuilder.build(contentSigner))
        certificate.verify(keyPair.public)

        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null, null) }
        keyStore.setKeyEntry(
            ALIAS,
            keyPair.private,
            PASSWORD.toCharArray(),
            arrayOf(certificate),
        )

        file.parentFile?.mkdirs()
        FileOutputStream(file).use { keyStore.store(it, PASSWORD.toCharArray()) }
        return KeyStore.getInstance(KEYSTORE_TYPE).apply {
            file.inputStream().use { load(it, PASSWORD.toCharArray()) }
        }
    }

    private const val KEYSTORE_TYPE = "PKCS12"
}
