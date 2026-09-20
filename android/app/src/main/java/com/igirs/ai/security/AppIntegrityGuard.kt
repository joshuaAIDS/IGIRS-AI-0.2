package com.igirs.ai.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

data class IntegrityReport(
    val isSignatureValid: Boolean,
    val isPackageValid: Boolean,
    val sha256Fingerprint: String,
    val isDebugBuild: Boolean
)

object AppIntegrityGuard {

    private const val TAG = "IGIRS.Integrity"
    private const val EXPECTED_PACKAGE_RELEASE = "com.igirs.ai"
    private const val EXPECTED_PACKAGE_DEBUG = "com.igirs.ai.debug"

    fun verifyIntegrity(context: Context): IntegrityReport {
        val packageName = context.packageName
        val isPackageValid = packageName == EXPECTED_PACKAGE_RELEASE || packageName == EXPECTED_PACKAGE_DEBUG

        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        val sha256Fingerprint = getSigningCertificateSha256(context)
        val isSignaturePresent = sha256Fingerprint.isNotEmpty()

        Log.i(TAG, "Integrity Check: pkg=$packageName, valid=$isPackageValid, debug=$isDebuggable, sigSHA256=$sha256Fingerprint")

        return IntegrityReport(
            isSignatureValid = isSignaturePresent,
            isPackageValid = isPackageValid,
            sha256Fingerprint = sha256Fingerprint,
            isDebugBuild = isDebuggable
        )
    }

    private fun getSigningCertificateSha256(context: Context): String {
        return try {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo.signingInfo ?: return ""
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            val firstSig = signatures?.firstOrNull() ?: return ""
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(firstSig.toByteArray())
            digest.joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating certificate digest: ${e.message}")
            ""
        }
    }
}
