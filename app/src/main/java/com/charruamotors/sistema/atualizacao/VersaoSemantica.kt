package com.charruamotors.sistema.atualizacao

/**
 * Versão `major.minor.patch` (com `-sufixo` de pré-release opcional), para comparar a tag da
 * GitHub Release (`v1.6`, `v1.6.1`, `v2.0.0-beta`) com o `versionName` do app (`1.5`).
 * Partes ausentes valem 0 (`1.5` = `1.5.0`); uma pré-release vem antes da versão final
 * (`1.6.0-beta` < `1.6.0`), como no SemVer. Objeto puro, testável na JVM.
 */
data class VersaoSemantica(
    val major: Int,
    val minor: Int = 0,
    val patch: Int = 0,
    val preRelease: String? = null,
) : Comparable<VersaoSemantica> {

    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "Versão com parte negativa: $major.$minor.$patch" }
    }

    override fun compareTo(other: VersaoSemantica): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
        return when {
            preRelease == other.preRelease -> 0
            preRelease == null -> 1 // a final ganha da pré-release
            other.preRelease == null -> -1
            else -> compararPreRelease(preRelease, other.preRelease)
        }
    }

    /** `1.5.0`, `1.6.0-beta`. */
    override fun toString(): String = "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    /** `1.5`, `1.5.1`, `2.0-beta`: sem o `.0` final do patch, como o `versionName` do app. */
    val curta: String
        get() = (if (patch == 0) "$major.$minor" else "$major.$minor.$patch") + (preRelease?.let { "-$it" } ?: "")

    companion object {
        /** `v1.5`, `V1.5.2`, `1.6.0-beta.1`, `1.5 (debug)`, `Charrua OBD Pro v1.5`... */
        private val REGEX = Regex("""(?:^|[^0-9A-Za-z])[vV]?(\d{1,6})(?:\.(\d{1,6}))?(?:\.(\d{1,6}))?(?:-([0-9A-Za-z.]+))?""")

        /** Interpreta o texto; null se não houver um número de versão. */
        fun deTexto(texto: String?): VersaoSemantica? {
            val t = texto?.trim().orEmpty()
            if (t.isEmpty()) return null
            val m = REGEX.find(" $t") ?: return null
            return VersaoSemantica(
                major = m.groupValues[1].toInt(),
                minor = m.groupValues[2].toIntOrNull() ?: 0,
                patch = m.groupValues[3].toIntOrNull() ?: 0,
                preRelease = m.groupValues[4].ifEmpty { null },
            )
        }

        /** [remota] é mais nova que [atual]? Textos que não são versão nunca são "mais novos". */
        fun ehMaisNova(remota: String?, atual: String?): Boolean {
            val r = deTexto(remota) ?: return false
            val a = deTexto(atual) ?: return true
            return r > a
        }

        /** Identificadores separados por ponto; numéricos comparam como número e vêm antes dos textuais. */
        private fun compararPreRelease(a: String, b: String): Int {
            val pa = a.split('.')
            val pb = b.split('.')
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrNull(i) ?: return -1
                val y = pb.getOrNull(i) ?: return 1
                val nx = x.toIntOrNull()
                val ny = y.toIntOrNull()
                val c = when {
                    nx != null && ny != null -> nx.compareTo(ny)
                    nx != null -> -1
                    ny != null -> 1
                    else -> x.compareTo(y)
                }
                if (c != 0) return c
            }
            return 0
        }
    }
}
