package com.nxuslab.dreymanager.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Erro do cofre com uma mensagem que pode ser mostrada ao usuário. */
class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Criptografia dos segredos do cofre.
 *
 * A chave atual (v2) exige que o usuário tenha se autenticado (biometria forte ou PIN/padrão/senha
 * do aparelho) nos últimos [AUTH_VALIDITY_SECONDS] segundos. Sem isso, o próprio Keystore recusa
 * cifrar ou decifrar, então a proteção não depende só da tela do app.
 *
 * Itens antigos (sem o prefixo `v2:`) foram cifrados com a chave legada, que não exigia
 * autenticação. Eles continuam legíveis e são recifrados com a chave nova por
 * `MainViewModel.migrateLegacyVault`, logo após o desbloqueio.
 */
object VaultCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val LEGACY_ALIAS = "nxuslife_vault_key"
    private const val ALIAS = "nxuslife_vault_key_v2"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "v2:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    const val AUTH_VALIDITY_SECONDS = 20

    fun isCurrentFormat(value: String): Boolean = value.startsWith(PREFIX)

    /** Cifra com a chave v2, usando o id do item como dado associado (impede trocar segredos entre itens). */
    fun encrypt(value: String, itemId: String): String = guard {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, currentKeyOrCreate())
        cipher.updateAAD(itemId.toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(value: String, itemId: String): String = guard {
        if (isCurrentFormat(value)) {
            val key = keyStore().getKey(ALIAS, null) as? SecretKey
                ?: throw VaultException("A chave do cofre não existe mais neste aparelho. Itens salvos antes disso não podem ser lidos.")
            decryptPayload(value.removePrefix(PREFIX), key, itemId)
        } else {
            val key = keyStore().getKey(LEGACY_ALIAS, null) as? SecretKey
                ?: throw VaultException("A chave antiga do cofre não existe mais neste aparelho. Este item não pode ser lido.")
            decryptPayload(value, key, aad = null)
        }
    }

    fun deleteLegacyKey() {
        runCatching { keyStore().deleteEntry(LEGACY_ALIAS) }
    }

    private fun decryptPayload(encoded: String, key: SecretKey, aad: String?): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        if (payload.size < IV_BYTES + TAG_BITS / 8) throw VaultException("Este item do cofre está corrompido.")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES))
        aad?.let { cipher.updateAAD(it.toByteArray(StandardCharsets.UTF_8)) }
        return cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES).toString(StandardCharsets.UTF_8)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun currentKeyOrCreate(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        AUTH_VALIDITY_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
                }
            }
            .build()
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
        } catch (e: GeneralSecurityException) {
            throw VaultException("Configure um bloqueio de tela (PIN, padrão ou senha) no aparelho para usar o cofre.", e)
        } catch (e: IllegalStateException) {
            throw VaultException("Configure um bloqueio de tela (PIN, padrão ou senha) no aparelho para usar o cofre.", e)
        }
    }

    /** Converte erros do Keystore em mensagens claras, em vez de falhar em silêncio. */
    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: VaultException) {
        throw e
    } catch (e: UserNotAuthenticatedException) {
        throw VaultException("Confirme sua identidade de novo para continuar. Se a digital não funcionar, use o PIN do aparelho.", e)
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw VaultException("O Android invalidou a chave do cofre (o bloqueio de tela foi removido ou trocado). Itens salvos antes disso não podem mais ser lidos.", e)
    } catch (e: AEADBadTagException) {
        throw VaultException("Não foi possível ler este item: ele foi alterado ou cifrado com outra chave.", e)
    } catch (e: IllegalArgumentException) {
        throw VaultException("Este item do cofre está corrompido.", e)
    } catch (e: GeneralSecurityException) {
        throw VaultException("Erro ao acessar o cofre: ${e.javaClass.simpleName}.", e)
    }
}
