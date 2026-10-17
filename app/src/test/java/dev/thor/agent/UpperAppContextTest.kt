package dev.thor.agent

import org.junit.Assert.*
import org.junit.Test

class UpperAppContextTest {
    private val target = AppContextTarget("game.upper", "valid", "window-42")
    private val info = AppContextMetadata("塞尔达", "1.2.3", "9007199254740993")

    @Test fun disabledAndConfigurationProbeDoNotReadAnything() {
        var reads = 0
        val context = UpperAppContext({ reads++; 0 }, { reads++; target }, { reads++; info })
        assertEquals("{}", context.read(false, false).toString())
        assertEquals("{}", context.read(true, true).toString())
        assertEquals(0, reads)
    }

    @Test fun upperDisplayIsIndependentOfSelectedLowerInputScreen() {
        val displays = mutableListOf<Int>()
        val context = UpperAppContext({ 0 }, { displays += it; target }, { info })
        val output = context.read(true, false)
        assertEquals(listOf(0, 0), displays)
        assertEquals("game.upper", output.getJSONObject("upperApp").getString("packageName"))
        assertEquals("塞尔达", output.getJSONObject("upperApp").getString("label"))
        assertEquals("1.2.3", output.getJSONObject("upperApp").getString("versionName"))
        assertEquals("9007199254740993", output.getJSONObject("upperApp").getString("versionCode"))
        assertFalse(output.has("snapshotId")); assertFalse(output.has("displaySnapshotId"))
        assertFalse(output.has("activity"))
    }

    @Test fun unknownOrAmbiguousNeverLookUpMetadataOrReusePreviousApp() {
        var now = target
        var lookups = 0
        val context = UpperAppContext({ 0 }, { now }, { lookups++; info })
        assertTrue(context.read(true, false).has("upperApp"))
        for (validity in listOf("unknown", "ambiguous")) {
            now = target.copy(validity = validity)
            val output = context.read(true, false)
            assertFalse(output.has("upperApp"))
            assertEquals(validity, output.getJSONObject("upperAppObservation").getString("status"))
        }
        assertEquals(1, lookups)
    }

    @Test fun missingAccessibilityAndDisplayAreUnavailable() {
        val absent = UpperAppContext({ 0 }, { null }, { error("Must not read metadata") }).read(true, false)
        assertEquals("unavailable", absent.getJSONObject("upperAppObservation").getString("status"))
        val noDisplay = UpperAppContext({ null }, { error("Must not inspect target") }, { info }).read(true, false)
        assertFalse(noDisplay.getJSONObject("upperAppObservation").has("displayId"))
        assertFalse(noDisplay.has("upperApp"))
    }

    @Test fun hiddenWindowDoesNotPublishAppAsVisibleForeground() {
        val output = UpperAppContext({ 0 }, { target.copy(visible = false) }, { error("Hidden target") }).read(true, false)
        assertFalse(output.has("upperApp"))
        assertEquals("unknown", output.getJSONObject("upperAppObservation").getString("status"))
    }

    @Test fun packageReadFailureDoesNotForgeLabelFromPackageName() {
        val output = UpperAppContext({ 0 }, { target }, { throw SecurityException() }).read(true, false)
        val app = output.getJSONObject("upperApp")
        assertEquals("game.upper", app.getString("packageName"))
        assertEquals("unavailable", app.getString("metadataStatus"))
        assertFalse(app.has("label")); assertFalse(app.has("versionCode"))
    }

    @Test fun foregroundChangeDuringPackageLookupDiscardsOldMetadata() {
        var now = target
        val output = UpperAppContext({ 0 }, { now }, { now = target.copy(packageName = "game.next"); info }).read(true, false)
        assertFalse(output.has("upperApp"))
        assertEquals("unknown", output.getJSONObject("upperAppObservation").getString("status"))
    }

    @Test fun windowChangeAndDisplayRecalibrationDiscardOldMetadata() {
        var now = target
        val window = UpperAppContext({ 0 }, { now }, { now = target.copy(identity = "new-window"); info }).read(true, false)
        assertFalse(window.has("upperApp"))
        var display = 0
        val moved = UpperAppContext({ display }, { target }, { display = 4; info }).read(true, false)
        assertFalse(moved.has("upperApp"))
        assertFalse(moved.getJSONObject("upperAppObservation").has("displayId"))
    }

    @Test fun eachRequestReadsFreshForegroundInsteadOfCachingIt() {
        var now = target
        val context = UpperAppContext({ 0 }, { now }, { info })
        assertEquals("game.upper", context.read(true, false).getJSONObject("upperApp").getString("packageName"))
        now = target.copy(packageName = "game.next", identity = "new-window")
        assertEquals("game.next", context.read(true, false).getJSONObject("upperApp").getString("packageName"))
    }

    @Test fun invalidPackageIdentityIsNotTruncatedIntoAnotherIdentity() {
        for (pkg in listOf("", "bad name", "a." + "b".repeat(255))) {
            val output = UpperAppContext({ 0 }, { target.copy(packageName = pkg) }, { error("Invalid identity") }).read(true, false)
            assertFalse(output.has("upperApp"))
            assertEquals("unknown", output.getJSONObject("upperAppObservation").getString("status"))
        }
    }

    @Test fun untrustedMetadataHasBoundedUnicodeAndNoControlCharacters() {
        val huge = AppContextMetadata("\n" + "😀".repeat(500), "中".repeat(500), "-1")
        val output = UpperAppContext({ 0 }, { target }, { huge }).read(true, false)
        val app = output.getJSONObject("upperApp")
        val label = app.getString("label")
        assertTrue(label.toByteArray().size <= 144)
        assertFalse(label.contains('\n'))
        assertEquals(0, label.length % 2)
        assertTrue(app.getString("versionName").toByteArray().size <= 96)
        assertFalse(app.has("versionCode"))
        assertTrue(output.toString().toByteArray().size < 640)
    }

    @Test fun metadataCacheIsStaticBoundedAndExpiresIncludingFailures() {
        var at = 1L; var loads = 0
        val cache = AppContextMetadataCache({ at }, { loads++; if (it == "missing") null else info }, ttlMs = 100, capacity = 2)
        assertEquals(info, cache.get("game.upper")); assertEquals(info, cache.get("game.upper")); assertEquals(1, loads)
        assertNull(cache.get("missing")); assertNull(cache.get("missing")); assertEquals(2, loads)
        cache.get("third"); cache.get("game.upper"); assertEquals(4, loads)
        at += 100; cache.get("game.upper"); assertEquals(5, loads)
        at = 0; cache.get("game.upper"); assertEquals(6, loads)
    }
}
