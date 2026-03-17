package com.coderred.andclaw.data.transfer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object TransferCrypto {
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'T'.code.toByte(), 'F'.code.toByte(), '2'.code.toByte())
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    private const val STREAM_BUFFER_SIZE = 32 * 1024
    private const val CHUNK_SIZE = 1 * 1024 * 1024 // 1MB per GCM chunk

    fun encryptFile(inputFile: File, outputFile: File, password: CharArray) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTES).also { random.nextBytes(it) }
        val baseIv = ByteArray(IV_LENGTH_BYTES).also { random.nextBytes(it) }

        try {
            val key = deriveKey(password, salt)

            FileOutputStream(outputFile).use { fos ->
                val header = DataOutputStream(fos)
                header.write(MAGIC)
                header.writeInt(PBKDF2_ITERATIONS)
                header.writeByte(SALT_LENGTH_BYTES)
                header.writeByte(IV_LENGTH_BYTES)
                header.write(salt)
                header.write(baseIv)
                header.writeInt(CHUNK_SIZE)
                header.flush()

                FileInputStream(inputFile).use { fis ->
                    val plainBuffer = ByteArray(CHUNK_SIZE)
                    var chunkIndex = 0L
                    while (true) {
                        val read = readChunk(fis, plainBuffer)
                        if (read == 0) break

                        val chunkIv = deriveChunkIv(baseIv, chunkIndex)
                        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
                        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, chunkIv))
                        val encrypted = cipher.doFinal(plainBuffer, 0, read)

                        fos.write(intToBytes(encrypted.size))
                        fos.write(encrypted)
                        chunkIndex++
                    }
                }
            }
        } catch (cause: Exception) {
            outputFile.delete()
            throw IOException("Failed to encrypt transfer artifact", cause)
        }
    }

    fun decryptToFile(inputFile: File, outputFile: File, password: CharArray) {
        try {
            FileInputStream(inputFile).use { fis ->
                val dis = DataInputStream(fis)
                val header = readChunkedHeader(dis, password)

                FileOutputStream(outputFile).use { fos ->
                    decryptChunkedStream(dis, fos, header.key, header.baseIv, header.chunkSize)
                }
            }
        } catch (cause: IllegalArgumentException) {
            outputFile.delete()
            throw cause
        } catch (cause: Exception) {
            outputFile.delete()
            throw IllegalArgumentException("Invalid password or corrupted transfer artifact", cause)
        }
    }

    internal fun decryptToBytes(inputFile: File, password: CharArray): ByteArray {
        try {
            FileInputStream(inputFile).use { fis ->
                val dis = DataInputStream(fis)
                val header = readChunkedHeader(dis, password)

                val output = java.io.ByteArrayOutputStream()
                decryptChunkedStream(dis, output, header.key, header.baseIv, header.chunkSize)
                return output.toByteArray()
            }
        } catch (cause: IllegalArgumentException) {
            throw cause
        } catch (cause: Exception) {
            throw IllegalArgumentException("Invalid password or corrupted transfer artifact", cause)
        }
    }

    internal fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return hexString(digest)
    }

    internal fun sha256HexStreaming(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            while (true) {
                val read = fis.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hexString(digest.digest())
    }

    private fun decryptChunkedStream(
        input: DataInputStream,
        output: OutputStream,
        key: SecretKeySpec,
        baseIv: ByteArray,
        chunkSize: Int,
    ) {
        val maxEncryptedChunkSize = chunkSize + GCM_TAG_LENGTH_BITS / 8
        var chunkIndex = 0L
        while (true) {
            val encLen = try {
                bytesToInt(input)
            } catch (_: EOFException) {
                break
            }
            if (encLen <= 0 || encLen > maxEncryptedChunkSize) {
                throw IllegalArgumentException("Invalid transfer artifact format")
            }

            val encData = ByteArray(encLen)
            input.readFully(encData)

            val chunkIv = deriveChunkIv(baseIv, chunkIndex)
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, chunkIv))
            val plain = cipher.doFinal(encData)
            output.write(plain)
            chunkIndex++
        }
    }

    private fun readChunkedHeader(input: DataInputStream, password: CharArray): ChunkedHeader {
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw IllegalArgumentException("Invalid transfer artifact format")
        }

        val iterations = input.readInt()
        if (iterations <= 0) {
            throw IllegalArgumentException("Invalid transfer artifact format")
        }

        val saltLength = input.readUnsignedByte()
        val ivLength = input.readUnsignedByte()
        if (saltLength <= 0 || ivLength <= 0) {
            throw IllegalArgumentException("Invalid transfer artifact format")
        }

        val salt = ByteArray(saltLength)
        val baseIv = ByteArray(ivLength)
        input.readFully(salt)
        input.readFully(baseIv)

        val chunkSize = input.readInt()
        if (chunkSize <= 0 || chunkSize > 16 * 1024 * 1024) {
            throw IllegalArgumentException("Invalid transfer artifact format")
        }

        val key = deriveKey(password, salt, iterations)
        return ChunkedHeader(key, baseIv, chunkSize)
    }

    private fun deriveChunkIv(baseIv: ByteArray, chunkIndex: Long): ByteArray {
        val iv = baseIv.copyOf()
        for (i in 0 until 8) {
            iv[iv.size - 1 - i] = (iv[iv.size - 1 - i].toInt() xor
                ((chunkIndex shr (i * 8)) and 0xFF).toInt()).toByte()
        }
        return iv
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte(),
        )
    }

    private fun bytesToInt(input: DataInputStream): Int {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return (bytes[0].toInt() and 0xFF shl 24) or
            (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or
            (bytes[3].toInt() and 0xFF)
    }

    private fun hexString(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            out.append(((byte.toInt() ushr 4) and 0xF).toString(16))
            out.append((byte.toInt() and 0xF).toString(16))
        }
        return out.toString()
    }

    private fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS,
    ): SecretKeySpec {
        val keySpec = PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val encoded = factory.generateSecret(keySpec).encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            keySpec.clearPassword()
        }
    }

    private class ChunkedHeader(
        val key: SecretKeySpec,
        val baseIv: ByteArray,
        val chunkSize: Int,
    )
}
