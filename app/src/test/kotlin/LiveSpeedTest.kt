import com.arcana.brain.app.liveSpeed
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveSpeedTest {
    @Test
    fun theSpeedCountsFromTheFirstPieceAndWaitsForEnoughOfThem() {
        assertEquals(null, liveSpeed(5, 1_000, 3_000), "too few pieces")
        assertEquals(null, liveSpeed(20, 1_000, 1_500), "too soon")
        assertEquals(null, liveSpeed(20, 0, 5_000), "no first piece yet")
        // 21 pieces over 2 s after the first one: 20 tokens / 2 s
        assertEquals(10.0, liveSpeed(21, 1_000, 3_000))
    }
}
