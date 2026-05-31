package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.GeneralSecurityException
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class EnvelopeEncryptionTest {

    companion object {
        private lateinit var kekAead: Aead
        private lateinit var otherKekAead: Aead

        @BeforeAll
        @JvmStatic
        fun setup() {
            AeadConfig.register()
            kekAead = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            otherKekAead = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }
    }

    private fun factory(kek: Aead = kekAead, ad: ByteArray = MessageDekFactory.DEFAULT_WRAP_AD) =
        MessageDekFactory(kek, ad)

    @Test
    fun `round-trip wrap unwrap and encrypt decrypt under the same DEK`() {
        val factory = factory()
        val (dek, wrapped) = factory.generate()
        val plaintext = "patient.account_number=12345".toByteArray()
        val ad = "msg:42:item:0".toByteArray()

        val ciphertext = dek.encrypt(plaintext, ad)
        val unwrappedDek = factory.unwrap(wrapped)

        assertContentEquals(plaintext, unwrappedDek.decrypt(ciphertext, ad))
    }

    @Test
    fun `decrypt fails with mismatched item associated data`() {
        val factory = factory()
        val (dek, _) = factory.generate()
        val plaintext = "row".toByteArray()

        val ciphertext = dek.encrypt(plaintext, "msg:1:item:0".toByteArray())

        assertThrows<GeneralSecurityException> {
            dek.decrypt(ciphertext, "msg:1:item:1".toByteArray())
        }
        assertThrows<GeneralSecurityException> {
            dek.decrypt(ciphertext, "msg:2:item:0".toByteArray())
        }
    }

    @Test
    fun `ciphertext from one DEK cannot be decrypted by another DEK`() {
        val factory = factory()
        val (dekA, _) = factory.generate()
        val (dekB, _) = factory.generate()
        val ad = "msg:1:item:0".toByteArray()

        val ciphertext = dekA.encrypt("secret".toByteArray(), ad)

        assertThrows<GeneralSecurityException> {
            dekB.decrypt(ciphertext, ad)
        }
    }

    @Test
    fun `unwrap fails when wrong KEK is used`() {
        val factory = factory()
        val (_, wrapped) = factory.generate()
        val mismatchedFactory = factory(kek = otherKekAead)

        assertThrows<GeneralSecurityException> {
            mismatchedFactory.unwrap(wrapped)
        }
    }

    @Test
    fun `unwrap fails when wrap associated data does not match`() {
        val factoryDomainA = factory(ad = "domain_a".toByteArray())
        val factoryDomainB = factory(ad = "domain_b".toByteArray())
        val (_, wrapped) = factoryDomainA.generate()

        assertThrows<GeneralSecurityException> {
            factoryDomainB.unwrap(wrapped)
        }
    }

    @Test
    fun `each generate produces a distinct DEK`() {
        val factory = factory()
        val (_, wrappedA) = factory.generate()
        val (_, wrappedB) = factory.generate()

        assertTrue(!wrappedA.contentEquals(wrappedB))
    }

    @Test
    fun `each encrypt produces a distinct ciphertext for the same plaintext and AD`() {
        val factory = factory()
        val (dek, _) = factory.generate()
        val plaintext = "row".toByteArray()
        val ad = "msg:1:item:0".toByteArray()

        val ct1 = dek.encrypt(plaintext, ad)
        val ct2 = dek.encrypt(plaintext, ad)

        assertTrue(!ct1.contentEquals(ct2))
    }
}
