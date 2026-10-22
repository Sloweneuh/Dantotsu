package ani.dantotsu.media.manga.mangareader

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import androidx.core.view.isVisible
import ani.dantotsu.databinding.ActivityMangaReaderBinding
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

object ReaderPageFilters {

    /**
     * The page filters (global, see ReaderDim / ReaderWarmth / ReaderGreyscale / ReaderInvert): the
     * dimmer and warm tint as overlays above the pages, greyscale and invert as a colour matrix on
     * the pages' own layer — the controls and indicators over them keep their colours.
     */
    fun apply(binding: ActivityMangaReaderBinding) {
        val dim = PrefManager.getVal<Float>(PrefName.ReaderDim).coerceIn(0f, 0.8f)
        binding.mangaReaderDim.alpha = dim
        binding.mangaReaderDim.isVisible = dim > 0f
        val warmth = PrefManager.getVal<Float>(PrefName.ReaderWarmth).coerceIn(0f, 1f)
        // A third at most: past that the tint drowns the page rather than warming it.
        binding.mangaReaderWarmth.alpha = warmth * 0.35f
        binding.mangaReaderWarmth.isVisible = warmth > 0f

        val greyscale = PrefManager.getVal<Boolean>(PrefName.ReaderGreyscale)
        val invert = PrefManager.getVal<Boolean>(PrefName.ReaderInvert)
        val pages = binding.mangaReaderSwipy
        if (!greyscale && !invert) {
            pages.setLayerType(View.LAYER_TYPE_NONE, null)
            return
        }
        val matrix = ColorMatrix()
        if (greyscale) matrix.setSaturation(0f)
        if (invert) matrix.postConcat(
            ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ))
        )
        pages.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        })
    }
}
