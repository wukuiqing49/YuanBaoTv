package com.wkq.bao.core.media.external

import org.junit.Assert.*
import org.junit.Test

class TelevisionPlayerAdapterTest {
    @Test fun matchesOnlySupportedXiaomiTelevisions() {
        assertTrue(TelevisionPlayerAdapter.supportsXiaomi(true,"Xiaomi","MiTV",29))
        assertTrue(TelevisionPlayerAdapter.supportsXiaomi(true,"other","Redmi",29))
        assertFalse(TelevisionPlayerAdapter.supportsXiaomi(false,"Xiaomi","Xiaomi",36))
        assertFalse(TelevisionPlayerAdapter.supportsXiaomi(true,"Sony","Sony",36))
        assertFalse(TelevisionPlayerAdapter.supportsXiaomi(true,"Xiaomi","Xiaomi",25))
    }
}
