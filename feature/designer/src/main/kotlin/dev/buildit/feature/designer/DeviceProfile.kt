package dev.buildit.feature.designer

/** Screen configurations the preview can simulate. Sizes in dp, density in dpi. */
enum class DeviceProfile(val widthDp: Int, val heightDp: Int, val densityDpi: Int) {
    Phone(411, 891, 420),
    SmallPhone(360, 640, 320),
    Foldable(673, 841, 420),
    Tablet(800, 1280, 240),
    ;

    fun size(landscape: Boolean): Pair<Int, Int> = if (landscape) heightDp to widthDp else widthDp to heightDp
}

data class PreviewConfig(
    val device: DeviceProfile = DeviceProfile.Phone,
    val landscape: Boolean = false,
    val night: Boolean = false,
    val zoom: Float = 1f,
)
