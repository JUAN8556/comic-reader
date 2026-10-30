package mx.jano.comics.ui.nav

/** Lo que las pantallas pueden pedir a la navegación. Lo implementa [NavViewModel]; las vistas previas usan uno vacío. */
interface Navigator {
    fun open(screen: Screen)
    fun openFolder(folderId: String)
    fun goToAncestor(folderId: String)
    fun openBook(bookId: String)
    fun replaceTop(screen: Screen)
    fun resetTo(screen: Screen)
    fun back()

    companion object {
        /** Navegador inerte para vistas previas. */
        val None: Navigator = object : Navigator {
            override fun open(screen: Screen) = Unit
            override fun openFolder(folderId: String) = Unit
            override fun goToAncestor(folderId: String) = Unit
            override fun openBook(bookId: String) = Unit
            override fun replaceTop(screen: Screen) = Unit
            override fun resetTo(screen: Screen) = Unit
            override fun back() = Unit
        }
    }
}
