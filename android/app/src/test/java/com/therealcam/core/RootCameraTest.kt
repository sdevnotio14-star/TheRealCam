package com.therealcam.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCameraTest {
    private val pkg = "com.therealcam"

    @Test
    fun appliedNeedsRootAndThePackageInTheAuxList() {
        val ok = RootShell.Result(true, "uid=0\nvendor.camera.aux.packagelist=com.android.camera,com.therealcam\ncamera.aux.packagelist=com.therealcam")
        assertTrue(RootCamera.applied(ok, pkg))
        val noRoot = RootShell.Result(false, "uid=10234\nvendor.camera.aux.packagelist=com.android.camera")
        assertFalse(RootCamera.applied(noRoot, pkg))
        val notWritten = RootShell.Result(true, "uid=0\nvendor.camera.aux.packagelist=com.android.camera")
        assertFalse(RootCamera.applied(notWritten, pkg))
        // A longer package name containing ours is not a match.
        val prefix = RootShell.Result(true, "uid=0\nvendor.camera.aux.packagelist=com.therealcam.other")
        assertFalse(RootCamera.applied(prefix, pkg))
    }
}
