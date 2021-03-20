package net.corda.core.node.services

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.CordaException
import net.corda.core.DoNotImplement
import net.corda.core.contracts.PartyAndReference
import net.corda.core.crypto.toStringShort
import net.corda.core.identity.*
import net.corda.core.internal.hash
import net.corda.core.utilities.contextLogger
import java.security.InvalidAlgorithmParameterException
import java.security.PublicKey
import java.security.cert.*
import java.util.*

/**
 * An identity service maintains a directory of parties by their associated distinguished name/public keys and thus
 * supports lookup of a party given its key, or name. The service also manages the certificates linking confidential
 * identities back to the well known identity.
 *
 * Well known identities in Corda are the public identity of a party, registered with the network map directory,
 * whereas confidential identities are distributed only on a need to know basis (typically between parties in
 * a transaction being built). See [NetworkMapCache] for retrieving well known identities from the network map.
 */
@DoNotImplement
interface IdentityService {
    val trustRoot: X509Certificate
    val trustAnchor: TrustAnchor
    val caCertStore: CertStore

    companion object {
        private val log = contextLogger()
    }

    /**
     * Verify and then store an identity.
     *
     * @param identity a party and the certificate path linking them to the network trust root.
     * @return the issuing entity, if known.
     * @throws IllegalArgumentException if the certificate path is invalid.
     */
    @Throws(CertificateExpiredException::class, CertificateNotYetValidException::class, InvalidAlgorithmParameterException::class)
    fun verifyAndRegisterIdentity(identity: PartyAndCertificate): PartyAndCertificate?

    /**
     * Asserts that an anonymous party maps to the given full party, by looking up the certificate chain associated with
     * the anonymous party and resolving it back to the given full party.
     *
     * @throws UnknownAnonymousPartyException if the anonymous party is not owned by the full party.
     */
    @Throws(UnknownAnonymousPartyException::class)
    fun assertOwnership(party: Party, anonymousParty: AnonymousParty) {
        @Suppress("DEPRECATION")
        val anonymousIdentity = certificateFromKey(anonymousParty.owningKey)
                ?: throw UnknownAnonymousPartyException("Unknown $anonymousParty")
        val issuingCert = anonymousIdentity.certPath.certificates[1]
        require(issuingCert.publicKey == party.owningKey) {
            "Issuing certificate's public key must match the party key ${party.owningKey.toStringShort()}."
        }
    }

    /**
     * Get all identities known to the service. This is expensive, and [partyFromKey] or [partyFromX500Name] should be
     * used in preference where possible.
     */
    fun getAllIdentities(): Iterable<PartyAndCertificate>

    /**
     * Resolves a public key to the well known identity [PartyAndCertificate] instance which is owned by the key.
     *
     * @param owningKey The [PublicKey] to determine well known identity for.
     * @return the party and certificate, or null if unknown.
     */
    fun certificateFromKey(owningKey: PublicKey): PartyAndCertificate?

    /**
     * Converts an owning [PublicKey] to the X500Name extended [Party] object if the [Party] has been
     * previously registered with the [IdentityService] either as a well known network map identity,
     * or as a part of flows creating and exchanging the identity.
     * @param key The owning [PublicKey] of the [Party].
     * @return Returns a [Party] with a matching owningKey if known, else returns null.
     */
    fun partyFromKey(key: PublicKey): Party?

    /**
     * Resolves a party name to the well known identity [Party] instance for this name. Where possible well known identity
     * lookup from name should be done from the network map (via [NetworkMapCache]) instead, as it is the authoritative
     * source of well known identities.
     *
     * @param name The [CordaX500Name] to determine well known identity for.
     * @return If known the canonical [Party] with that name, else null.
     */
    fun wellKnownPartyFromX500Name(name: CordaX500Name): Party?

    /**
     * Resolves a (optionally) confidential identity to the corresponding well known identity [Party].
     * It transparently handles returning the well known identity back if a well known identity is passed in.
     *
     * @param party identity to determine well known identity for.
     * @return well known identity, if found.
     */
    fun wellKnownPartyFromAnonymous(party: AbstractParty): Party? {
        // The original version of this would return the party as-is if it was a Party (rather than AnonymousParty),
        // however that means that we don't verify that we know who owns the key. As such as now enforce turning the key
        // into a party, and from there figure out the well known party.
        log.debug("Attempting to find wellKnownParty for: ${party.owningKey.hash}")
        val candidate = partyFromKey(party.owningKey)
        // TODO: This should be done via the network map cache, which is the authoritative source of well known identities
        return if (candidate != null) {
            require(party.nameOrNull() == null || party.nameOrNull() == candidate.name) {
                "Candidate party $candidate does not match expected $party"
            }
            wellKnownPartyFromX500Name(candidate.name)
        } else {
            null
        }
    }

    /**
     * Resolves a (optionally) confidential identity to the corresponding well known identity [Party].
     * Convenience method which unwraps the [Party] from the [PartyAndReference] and then resolves the
     * well known identity as normal.
     * It transparently handles returning the well known identity back if a well known identity is passed in.
     *
     * @param partyRef identity (and reference, which is unused) to determine well known identity for.
     * @return the well known identity, or null if unknown.
     */
    fun wellKnownPartyFromAnonymous(partyRef: PartyAndReference): Party? = wellKnownPartyFromAnonymous(partyRef.party)

    /**
     * Resolve the well known identity of a party. Throws an exception if the party cannot be identified.
     * If the party passed in is already a well known identity (i.e. a [Party]) this returns it as-is.
     *
     * @return the well known identity.
     * @throws IllegalArgumentException
     */
    fun requireWellKnownPartyFromAnonymous(party: AbstractParty): Party {
        return wellKnownPartyFromAnonymous(party)
                ?: throw IllegalStateException("Could not deanonymise party ${party.owningKey.toStringShort()}")
    }

    /**
     * Returns a list of candidate matches for a given string, with optional fuzzy(ish) matching. Fuzzy matching may
     * get smarter with time e.g. to correct spelling errors, so you should not hard-code indexes into the results
     * but rather show them via a user interface and let the user pick the one they wanted.
     *
     * @param query The string to check against the X.500 name components
     * @param exactMatch If true, a case sensitive match is done against each component of each X.500 name.
     */
    fun partiesFromName(query: String, exactMatch: Boolean): Set<Party>

    /**
     * Registers a mapping in the database between the provided [PublicKey] and [Party] if one does not already exist. If an entry
     * exists for the supplied [PublicKey] but the associated [Party] does not match the one supplied to the method then a warning will be
     * logged and the operation will not be carried out as a key can only ever be registered to one [Party].
     *
     * This method also optionally adds a mapping from [PublicKey] to external ID if one is provided. Lastly, the [PublicKey] is
     * also stored (as well as the [PublicKey] hash).
     *
     * @param publicKey The public publicKey that will be registered to the supplied [Party]
     * @param party The party that the supplied public publicKey will be registered to
     * @param externalId The [UUID] that the supplied public key can be optionally registered to
     * @throws IllegalArgumentException if the public publicKey is already registered to a party that does not match the supplied party
     */
    @Throws(IllegalArgumentException::class)
    fun registerKey(publicKey: PublicKey, party: Party, externalId: UUID? = null)

    /**
     * This method allows lookups of [PublicKey]s to an associated "external ID" / [UUID]. Providing a [PublicKey] that is unknown by the node
     * or is not mapped to an external ID will return null. Otherwise, if the [PublicKey] has been mapped to an external ID, then the [UUID]
     * for that external ID will be returned. The method looks up keys generated by the node as well as keys generated on other nodes and
     * registered with the [IdentityService].
     *
     * @param publicKey the [PublicKey] used to perform the lookup to external ID
     */
    @Suspendable
    fun externalIdForPublicKey(publicKey: PublicKey): UUID?

    /**
     * This method returns all the [PublicKey]s which have been mapped to the supplied external ID.
     *
     * @param externalId the external ID to lookup [PublicKey]s for
     */
    fun publicKeysForExternalId(externalId: UUID): Iterable<PublicKey>
}

class UnknownAnonymousPartyException(message: String) : CordaException(message)
