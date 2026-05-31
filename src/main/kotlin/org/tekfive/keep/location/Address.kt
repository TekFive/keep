package org.tekfive.keep.location

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.toJsonObject
import org.tekfive.kviash.http.HttpRequestParameters
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

data class Address(
    @JvmField
    val street: String?,
    @JvmField
    val city: String?,
    @JvmField
    val state: State?,
    @JvmField
    val zip: String?,
) {

    val hasFullAddress: Boolean
        get() = !street.isNullOrBlank() && !city.isNullOrBlank() && state != null && !zip.isNullOrBlank()

    val hasData: Boolean
        get() = !street.isNullOrBlank() || !city.isNullOrBlank() || state != null || !zip.isNullOrBlank()

    constructor() : this(null, null, null, null)

    fun toJson(): JsonObject = mapOf(
        "street" to street,
        "city" to city,
        "state" to state?.name,
        "zip" to zip,
    ).toJsonObject()

    fun toJsonString(): String {
        return toJson().toJsonString()
    }

    companion object {
        operator fun invoke(parameters: HttpRequestParameters): Address {
            val street = parameters["street"]
            val city = parameters["city"]
            val state = State.mapOptional(parameters.getInt("state"))
            val zip = parameters["zip"]
            return Address(street, city, state, zip)
        }

        operator fun invoke(json: JsonObject?): Address {
            if (json == null) {
                return Address()
            }
            val street = json["street"].string
            val city = json["city"].string
            val stateId = json["state"].int
            val state = State.mapOptional(stateId)
            val zip = json["zipCode"].string
            return Address(street, city, state, zip)
        }
    }
}

@OptIn(ExperimentalContracts::class)
fun Address?.isNullOrEmpty(): Boolean {
    contract {
        returns(false) implies (this@isNullOrEmpty != null)
    }
    return this == null || !hasData
}

@OptIn(ExperimentalContracts::class)
fun Address?.isNotNullOrEmpty(): Boolean {
    contract {
        returns(true) implies (this@isNotNullOrEmpty != null)
    }
    return !isNullOrEmpty()
}


@OptIn(ExperimentalContracts::class)
fun Address?.isNotNullAndFullAddress(): Boolean {
    contract {
        returns(true) implies (this@isNotNullAndFullAddress != null)
    }
    return this != null && hasFullAddress
}

@OptIn(ExperimentalContracts::class)
fun Address?.nullEquivalent(address: Address?): Boolean {
    return if (this == null || !hasData) {
        address.isNotNullOrEmpty()
    } else if (address.isNullOrEmpty()) {
        false
    } else {
        equals(address)
    }
}
