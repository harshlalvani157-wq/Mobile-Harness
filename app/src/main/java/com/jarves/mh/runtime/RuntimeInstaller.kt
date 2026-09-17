package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * RuntimeInstaller handles downloading, checksum verification, and extracting
 * the Debian 13 (Trixie) Linux rootfs environment for Mobile-Harness.
 */
class RuntimeInstaller(private val context: Context) {

    companion object {
        private const val TAG = "RuntimeInstaller"

        // EXACT DEBIAN 13 (TRIXIE) ARM64 CONFIGURATION
        const val DEFAULT_ROOTFS_NAME = "debian-aarch64-pd-v1.10.1.tar.xz"
        const val DEFAULT_ROOTFS_URL = "https://github.com/termux/proot-distro/releases/download/v1.10.1/debian-aarch64-pd-v1.10.1.tar.xz"
        const val DEFAULT_ROOTFS_SHA256 = "0019dfc4b32d63c1392aa264aed2253c1e0c2fb09216f8e2cc269bbfb8bb49b5"

        const val ROOTFS_DIR_NAME = "rootfs"
    }

    data class InstallProgress(
        val step: String,
        val progressPercent: Int,
        val isComplete: Boolean = false,
        val error: String? = null
    )

    val rootfsDir: File
        get() = File(context.filesDir, ROOTFS_DIR_NAME)

    fun isInstalled(): Boolean {
        val etcDebianVersion = File(rootfsDir, "etc/debian_version")
        val binBash = File(rootfsDir, "bin/bash")
        return etcDebianVersion.exists() || binBash.exists()
    }

    /**
     * Executes the download, verification, and extraction flow.
     */
    suspend fun installRuntime(
        customUrl: String? = null,
        customSha256: String? = null,
        onProgress: (InstallProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val downloadUrl = customUrl ?: DEFAULT_ROOTFS_URL
            val expectedSha256 = customSha256 ?: DEFAULT_ROOTFS_SHA256

            val cacheArchive = File(context.cacheDir, DEFAULT_ROOTFS_NAME)

            // Step 1: Download
            onProgress(InstallProgress("Downloading Debian 13 Rootfs...", 10))
            Log.i(TAG, "Starting download from: $downloadUrl")
            downloadFile(downloadUrl, cacheArchive) { downloadedBytes, totalBytes ->
                val percent = if (totalBytes > 0) ((downloadedBytes * 60) / totalBytes).toInt() + 10 else 35
                onProgress(InstallProgress("Downloading: ${downloadedBytes / (1024 * 1024)} MB", percent))
            }

            // Step 2: Verify Checksum
            onProgress(InstallProgress("Verifying SHA-256 integrity...", 75))
            if (expectedSha256.isNotBlank() && expectedSha256 != "SKIP_VERIFY") {
                val actualSha256 = computeSha256(cacheArchive)
                Log.i(TAG, "Computed SHA256: $actualSha256 | Expected: $expectedSha256")
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    val errorMsg = "SHA-256 verification failed!\nExpected: $expectedSha256\nGot: $actualSha256"
                    Log.e(TAG, errorMsg)
                    onProgress(InstallProgress("Checksum error", 75, isComplete = false, error = errorMsg))
                    return@withContext false
                }
            }

            // Step 3: Extract Rootfs
            onProgress(InstallProgress("Extracting Debian 13 into rootfs...", 80))
            if (!rootfsDir.exists()) {
                rootfsDir.mkdirs()
            }

            // Unpacks .tar.xz using Android's native toybox tar
            val extractSuccess = extractArchive(cacheArchive, rootfsDir)
            if (!extractSuccess) {
                onProgress(InstallProgress("Extraction failed", 80, isComplete = false, error = "Failed to unpack .tar.xz archive"))
                return@withContext false
            }

            // Step 4: Cleanup downloaded archive to save phone storage
            cacheArchive.delete()

            onProgress(InstallProgress("Debian 13 successfully installed!", 100, isComplete = true))
            Log.i(TAG, "Debian 13 installation completed.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Installation failed with error", e)
            onProgress(InstallProgress("Error during install", 0, isComplete = false, error = e.localizedMessage))
            false
        }
    }

    private fun downloadFile(urlStr: String, destination: File, onUpdate: (Long, Long) -> Unit) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.connect()

        val totalBytes = conn.contentLength.toLong()
        var downloadedBytes: Long = 0

        conn.inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    onUpdate(downloadedBytes, totalBytes)
                }
                output.flush()
            }
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractArchive(archiveFile: File, outputDirectory: File): Boolean {
        return try {
            // Using `tar -xf` to automatically unpack .tar.xz or .tar.gz archives
            val process = ProcessBuilder(
                "tar", "-xf", archiveFile.absolutePath, "-C", outputDirectory.absolutePath
            ).redirectErrorStream(true).start()

            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            false
        }
    }
}