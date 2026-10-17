package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class SnapshotsObservationDiagnosticsTest {
    private val upper=DisplayFact(0,"upper-private-identity",800,600,0,240)
    private val lower=DisplayFact(4,"lower-private-identity",800,600,0,240)
    private val initial=Target("private.app","valid","private-content",TouchableBounds(0,0,800,600),"private-window:structure:1","interaction-1")
    @Test fun isolatesStructureCounterFromUnchangedLiveWindowLayoutWithoutExportingStrings() {
        var target=initial
        val snapshots=Snapshots(Clock {0}) {target}.apply {update(listOf(upper,lower));calibrate(0,4)}
        val observed=snapshots.capture();target=target.copy(layoutRevision="private-window:structure:2")
        val diagnostic=snapshots.observationDiagnostics(observed,"upper")
        assertEquals(false,diagnostic["sameStructure"])
        assertEquals(true,diagnostic["layoutWithoutCounterSame"])
        assertEquals(false,diagnostic["structureCounterSame"])
        assertEquals(true,diagnostic["clipSame"])
        assertEquals(true,diagnostic["displaySame"])
        assertTrue(diagnostic.values.all {it is Boolean})
        assertFalse(diagnostic.toString().contains("private-"));assertFalse(diagnostic.toString().contains("private.app"))
        assertThrows(ToolFailure::class.java) {snapshots.validateObservation(observed.id)} // no guard relaxed
    }
    @Test fun reportsExpiryRoleAndCacheLossIndependently() {
        var now=0L
        val snapshots=Snapshots(Clock {now}) {initial}.apply {update(listOf(upper,lower));calibrate(0,4)}
        val observed=snapshots.capture();now=Snapshots.OBSERVATION_TTL_MS
        assertEquals(false,snapshots.observationDiagnostics(observed,"lower")["notExpired"])
        assertEquals(false,snapshots.observationDiagnostics(observed,"lower")["roleSame"])
        repeat(33){snapshots.capture()}
        assertEquals(false,snapshots.observationDiagnostics(observed,"upper")["cachedSnapshotPresent"])
    }
    @Test fun distinguishesRealWindowClippingAndInteractionChanges() {
        var target=initial
        val snapshots=Snapshots(Clock {0}) {target}.apply {update(listOf(upper,lower));calibrate(0,4)}
        val observed=snapshots.capture()
        target=target.copy(layoutRevision="different-private-window:structure:1",touchableBounds=TouchableBounds(0,20,800,600),interactionRevision="changed")
        val diagnostic=snapshots.observationDiagnostics(observed,"upper")
        assertEquals(false,diagnostic["layoutWithoutCounterSame"])
        assertEquals(false,diagnostic["clipSame"])
        assertEquals(false,diagnostic["interactionRevisionSame"])
        assertEquals(true,diagnostic["structureCounterSame"])
    }
}
