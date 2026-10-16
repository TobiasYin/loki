package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class DisplayRolePolicyTest {
    private val upper=DisplayFact(0,"Internal",1920,1080,1,369)
    private val lower=DisplayFact(4,"Screen-2",1240,1080,1,369)
    @Test fun thorDefaultWorksWithUnrelatedVirtualDisplayAndSparseIds() {
        val facts=listOf(upper,lower,DisplayFact(15,"private-app",1,1,0,1))
        assertEquals(upper to lower,DisplayRolePolicy.resolve(facts,null,null,false,true))
        assertNull(DisplayRolePolicy.resolve(facts,null,null,false,false))
        assertNull(DisplayRolePolicy.resolve(listOf(upper),null,null,false,true))
        assertNull(DisplayRolePolicy.resolve(facts+lower.copy(id=8),null,null,false,true))
    }
    @Test fun savedMappingNeverSilentlyFallsBackWhenAnExternalPanelDisappears() {
        assertNull(DisplayRolePolicy.resolve(listOf(upper,lower),"external","Screen-2",true,true))
        assertNull(DisplayRolePolicy.resolve(listOf(upper,lower),null,"Screen-2",true,true))
        assertNull(DisplayRolePolicy.resolve(listOf(upper,lower),"Internal","Internal",true,true))
    }
    @Test fun restoredHintsUseNewDescriptorsAndNeverStaleGeometryOrIds() {
        val rotated=lower.copy(id=9,width=1080,height=1240,rotation=0)
        assertEquals(upper to rotated,DisplayRolePolicy.resolve(listOf(upper,rotated),"Internal","Screen-2",true,true))
    }
}
