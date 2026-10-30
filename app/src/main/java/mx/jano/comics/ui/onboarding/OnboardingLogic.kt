package mx.jano.comics.ui.onboarding

import mx.jano.comics.drive.DriveFile
import java.text.Normalizer

object OnboardingLogic {
    /**
     * Nombres con los que se busca la carpeta raíz en Mi unidad: «Cómics» compuesto (NFC) y descompuesto (NFD, como lo
     * dejan algunas subidas desde macOS) y «Comics» sin tilde.
     */
    fun rootNameCandidates(base: String = "Cómics"): List<String> = listOf(
        Normalizer.normalize(base, Normalizer.Form.NFC),
        Normalizer.normalize(base, Normalizer.Form.NFD),
        "Comics",
    ).distinct()

    /** Solo carpetas, sin las ocultas (las que empiezan por punto), en orden natural. */
    fun foldersOnly(children: List<DriveFile>, comparator: Comparator<String>): List<DriveFile> =
        children.filter { it.isFolder && !it.name.startsWith(".") }.sortedWith(compareBy(comparator) { it.name })
}
