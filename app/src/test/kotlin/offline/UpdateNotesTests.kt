package org.jellyfin.androidtv.offline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.offline.update.markdownToPlainText

class UpdateNotesTests : FunSpec({
	test("changelog markdown is shown as plain text") {
		val markdown = """
			### Nuevo
			- **Descargar desde un episodio**: mantén pulsado OK y elige
			  *Descargar desde aquí*. Se saltan los episodios que ya tienes.
			- Otra mejora
		""".trimIndent()

		markdownToPlainText(markdown) shouldBe """
			Nuevo
			• Descargar desde un episodio: mantén pulsado OK y elige Descargar desde aquí. Se saltan los episodios que ya tienes.
			• Otra mejora
		""".trimIndent()
	}
})
