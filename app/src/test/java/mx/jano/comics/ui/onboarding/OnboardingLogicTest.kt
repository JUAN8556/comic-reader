package mx.jano.comics.ui.onboarding

import mx.jano.comics.drive.DriveFile
import mx.jano.comics.ui.FallbackNatural
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class OnboardingLogicTest {
    @Test fun rootNameIsSearchedComposedDecomposedAndWithoutAccent() {
        val names = OnboardingLogic.rootNameCandidates()
        assertEquals(3, names.size)
        assertTrue(Normalizer.isNormalized(names[0], Normalizer.Form.NFC))
        assertEquals(Normalizer.normalize("Cómics", Normalizer.Form.NFD), names[1])
        assertEquals("Comics", names[2])
        assertEquals(listOf("Comics"), OnboardingLogic.rootNameCandidates("Comics"))
    }

    @Test fun onlyVisibleFoldersInNaturalOrder() {
        val folder = "application/vnd.google-apps.folder"
        val children = listOf(
            DriveFile("1", "Carpeta 10", folder),
            DriveFile("2", "suelto.cbz", "application/zip"),
            DriveFile("3", ".indice", folder),
            DriveFile("4", "Carpeta 2", folder),
            DriveFile("5", "Álbumes", folder),
        )
        assertEquals(
            listOf("Álbumes", "Carpeta 2", "Carpeta 10"),
            OnboardingLogic.foldersOnly(children, FallbackNatural).map { it.name },
        )
    }
}
