package com.charruamotors.obd.pid

/**
 * Sensor semântico, independente de como a ECU o entrega. O mesmo RPM chega pelo `010C` (OBD2
 * padrão), pelo `210C` (modo 21 M-OBD/KWP) ou pelo `22F40C` (modo 22 UDS); a chave liga todos
 * eles ao PID canônico de `pids_padrao.json` (campo `"chave"`), e é nesse lugar (`010C`) que o
 * valor é publicado — mostradores, gráfico, computador de bordo, 0–100, log CSV e filas de
 * polling funcionam igual em qualquer camada da cascata.
 */
enum class ChaveSemantica(val descricao: String) {
    RPM("Rotação do motor"),
    VELOCIDADE("Velocidade do veículo"),
    MAP("Pressão do coletor (MAP / turbo)"),
    TEMP_MOTOR("Temperatura do motor"),
    CARGA("Carga do motor"),
    MAF("Fluxo de massa de ar"),
    BORBOLETA("Borboleta / acelerador"),
    IAT("Temperatura do ar de admissão"),
    RAIL("Pressão do common rail"),
    TEMP_OLEO("Temperatura do óleo"),
    TENSAO("Tensão da bateria"),
    ;

    companion object {
        /** `"RPM"` → [RPM] (sem diferenciar maiúsculas); null se vazio. */
        fun deTexto(texto: String?): ChaveSemantica? {
            val t = texto?.trim()?.uppercase().orEmpty()
            if (t.isEmpty()) return null
            return entries.firstOrNull { it.name == t }
                ?: throw IllegalArgumentException("Chave semântica desconhecida: $texto (válidas: ${entries.joinToString()})")
        }
    }
}
