package com.sleepysoong.hoard.ui

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.MainActivity
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Every launch asks for missing permissions (here: notifications; Termux isn't installed) — once, not on rotation. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PermissionGateTest {
    @Before fun seed() { HoardRepository.resetForTests(); TestData.seed() }

    @Test fun launchAsksForMissingPermissionsOnce() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var requested: Array<String>? = null
            scenario.onActivity { a ->
                shadowOf(android.os.Looper.getMainLooper()).idle()
                requested = shadowOf(a).lastRequestedPermission?.requestedPermissions
            }
            assertNotNull("asked at launch", requested)
            assertTrue(requested!!.toList().contains(Manifest.permission.POST_NOTIFICATIONS))
            assertTrue("Termux not installed → not asked", requested!!.none { it.contains("termux") })
            // Rotation: same launch, no second request.
            scenario.onActivity { shadowOf(it).clearNextStartedActivities() }
            scenario.recreate()
            scenario.onActivity { a ->
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals(null, shadowOf(a).lastRequestedPermission)
            }
        }
    }
}
