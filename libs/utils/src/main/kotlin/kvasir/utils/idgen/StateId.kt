package kvasir.utils.idgen

import com.github.f4b6a3.uuid.UuidCreator

object StateId {

    fun generate(): String {
        return UuidCreator.getTimeOrderedEpoch().toString()
    }

}