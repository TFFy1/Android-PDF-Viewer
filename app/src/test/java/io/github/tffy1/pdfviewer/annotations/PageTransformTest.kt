package io.github.tffy1.pdfviewer.annotations

import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.core.model.PageSize
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PageTransformTest {
    private val eps = 1e-3f

    /** A 200 x 300 crop box whose origin is not at (0, 0). */
    private val crop = UserSpaceRect(50f, 100f, 250f, 400f)

    private fun assertPoint(expected: UserSpacePoint, actual: UserSpacePoint) {
        assertEquals("x", expected.x, actual.x, eps)
        assertEquals("y", expected.y, actual.y, eps)
    }

    private fun assertPoint(expected: PagePoint, actual: PagePoint) {
        assertEquals("x", expected.x, actual.x, eps)
        assertEquals("y", expected.y, actual.y, eps)
    }

    @Test
    fun pageSizeSwapsForQuarterTurns() {
        assertEquals(PageSize(200f, 300f), PageTransform(crop, 0).pageSize)
        assertEquals(PageSize(300f, 200f), PageTransform(crop, 90).pageSize)
        assertEquals(PageSize(200f, 300f), PageTransform(crop, 180).pageSize)
        assertEquals(PageSize(300f, 200f), PageTransform(crop, 270).pageSize)
    }

    @Test
    fun rotation0MapsTopLeftToCropUpperLeft() {
        val t = PageTransform(crop, 0)
        assertPoint(UserSpacePoint(50f, 400f), t.toUserSpace(PagePoint(0f, 0f)))
        assertPoint(UserSpacePoint(250f, 100f), t.toUserSpace(PagePoint(200f, 300f)))
        assertPoint(UserSpacePoint(60f, 380f), t.toUserSpace(PagePoint(10f, 20f)))
    }

    @Test
    fun rotation90() {
        // Rotated clockwise: the unrotated top-left corner (50, 400) is shown at the top-right.
        val t = PageTransform(crop, 90)
        assertPoint(UserSpacePoint(50f, 400f), t.toUserSpace(PagePoint(300f, 0f)))
        // Displayed top-left is the unrotated bottom-left corner.
        assertPoint(UserSpacePoint(50f, 100f), t.toUserSpace(PagePoint(0f, 0f)))
        assertPoint(UserSpacePoint(250f, 100f), t.toUserSpace(PagePoint(0f, 200f)))
        assertPoint(UserSpacePoint(250f, 400f), t.toUserSpace(PagePoint(300f, 200f)))
    }

    @Test
    fun rotation180() {
        val t = PageTransform(crop, 180)
        assertPoint(UserSpacePoint(250f, 100f), t.toUserSpace(PagePoint(0f, 0f)))
        assertPoint(UserSpacePoint(50f, 400f), t.toUserSpace(PagePoint(200f, 300f)))
    }

    @Test
    fun rotation270() {
        val t = PageTransform(crop, 270)
        // Displayed top-left is the unrotated top-right corner.
        assertPoint(UserSpacePoint(250f, 400f), t.toUserSpace(PagePoint(0f, 0f)))
        assertPoint(UserSpacePoint(50f, 400f), t.toUserSpace(PagePoint(0f, 200f)))
        assertPoint(UserSpacePoint(250f, 100f), t.toUserSpace(PagePoint(300f, 0f)))
    }

    @Test
    fun roundTripsForEveryRotation() {
        val samples = listOf(PagePoint(0f, 0f), PagePoint(12.5f, 33f), PagePoint(199f, 1f), PagePoint(77f, 150f))
        for (rotation in listOf(0, 90, 180, 270, -90, 360, 450)) {
            val t = PageTransform(crop, rotation)
            for (p in samples) {
                assertPoint(p, t.toPageSpace(t.toUserSpace(p)))
            }
            for (u in listOf(UserSpacePoint(50f, 100f), UserSpacePoint(123f, 321f), UserSpacePoint(250f, 400f))) {
                assertPoint(u, t.toUserSpace(t.toPageSpace(u)))
            }
        }
    }

    @Test
    fun cornersOfTheDisplayedPageMapToCropBoxCorners() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val t = PageTransform(crop, rotation)
            val size = t.pageSize
            val corners = listOf(
                PagePoint(0f, 0f), PagePoint(size.width, 0f),
                PagePoint(0f, size.height), PagePoint(size.width, size.height),
            ).map { t.toUserSpace(it) }
            assertEquals(setOf(50f, 250f), corners.map { it.x }.toSet())
            assertEquals(setOf(100f, 400f), corners.map { it.y }.toSet())
        }
    }

    @Test
    fun normalizeRotation() {
        assertEquals(0, PageTransform.normalizeRotation(0))
        assertEquals(270, PageTransform.normalizeRotation(-90))
        assertEquals(90, PageTransform.normalizeRotation(450))
        assertEquals(0, PageTransform.normalizeRotation(720))
        assertEquals(0, PageTransform.normalizeRotation(45))
    }

    @Test
    fun invertedCropBoxIsNormalized() {
        val t = PageTransform(UserSpaceRect(250f, 400f, 50f, 100f), 0)
        assertEquals(PageSize(200f, 300f), t.pageSize)
        assertPoint(UserSpacePoint(50f, 400f), t.toUserSpace(PagePoint(0f, 0f)))
    }

    @Test
    fun rectToUserSpace() {
        val t = PageTransform(crop, 90)
        val r = t.toUserSpace(PageRect(10f, 20f, 40f, 30f))
        // x' = llx + y, y' = lly + x for /Rotate 90.
        assertEquals(UserSpaceRect(70f, 110f, 80f, 140f), r)
    }

    @Test
    fun quadPointsFollowDisplayOrientation() {
        val rect = PageRect(10f, 20f, 110f, 32f)
        // Unrotated: UL, UR, LL, LR with "upper" having the larger y.
        assertArrayEquals(
            floatArrayOf(60f, 380f, 160f, 380f, 60f, 368f, 160f, 368f),
            PageTransform(crop, 0).quadPoints(rect),
            eps,
        )
        // Rotated 90: text runs up the unrotated page; upper edge of the line is at larger x.
        val q = PageTransform(crop, 90).quadPoints(rect)
        val ul = UserSpacePoint(q[0], q[1])
        val ur = UserSpacePoint(q[2], q[3])
        val ll = UserSpacePoint(q[4], q[5])
        assertPoint(UserSpacePoint(70f, 110f), ul)
        assertPoint(UserSpacePoint(70f, 210f), ur)
        assertPoint(UserSpacePoint(82f, 110f), ll)
        for (rotation in listOf(0, 90, 180, 270)) {
            val t = PageTransform(crop, rotation)
            val quad = t.quadPoints(rect)
            // Mapping the quad back gives the rect's corners in UL, UR, LL, LR order.
            val back = (0 until 4).map { t.toPageSpace(UserSpacePoint(quad[2 * it], quad[2 * it + 1])) }
            assertPoint(PagePoint(10f, 20f), back[0])
            assertPoint(PagePoint(110f, 20f), back[1])
            assertPoint(PagePoint(10f, 32f), back[2])
            assertPoint(PagePoint(110f, 32f), back[3])
        }
    }

    @Test
    fun boundingRect() {
        assertEquals(null, UserSpaceRect.bounding(emptyList()))
        assertEquals(
            UserSpaceRect(-1f, 2f, 5f, 9f),
            UserSpaceRect.bounding(listOf(UserSpacePoint(5f, 2f), UserSpacePoint(-1f, 9f), UserSpacePoint(0f, 3f))),
        )
    }
}
