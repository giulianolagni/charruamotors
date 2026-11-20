package com.charruamotors.obd

/** Descrições básicas, em português, dos códigos de falha (DTCs) mais comuns. */
object DescricaoDtc {

    private val conhecidos = mapOf(
        "P0100" to "Mau funcionamento do circuito do sensor de fluxo de massa de ar (MAF)",
        "P0101" to "Sensor MAF fora da faixa de desempenho",
        "P0102" to "Sinal baixo no circuito do sensor MAF",
        "P0105" to "Mau funcionamento do sensor de pressão absoluta do coletor (MAP)",
        "P0110" to "Mau funcionamento do sensor de temperatura do ar de admissão",
        "P0115" to "Mau funcionamento do sensor de temperatura do líquido de arrefecimento",
        "P0116" to "Sensor de temperatura do arrefecimento fora da faixa",
        "P0117" to "Sinal baixo no sensor de temperatura do arrefecimento",
        "P0120" to "Mau funcionamento do sensor de posição da borboleta (TPS)",
        "P0125" to "Temperatura do arrefecimento insuficiente para controle em malha fechada",
        "P0130" to "Mau funcionamento do circuito da sonda lambda (banco 1, sensor 1)",
        "P0131" to "Sonda lambda com tensão baixa (banco 1, sensor 1)",
        "P0133" to "Resposta lenta da sonda lambda (banco 1, sensor 1)",
        "P0135" to "Mau funcionamento do aquecedor da sonda lambda (banco 1, sensor 1)",
        "P0171" to "Mistura pobre (banco 1)",
        "P0172" to "Mistura rica (banco 1)",
        "P0174" to "Mistura pobre (banco 2)",
        "P0175" to "Mistura rica (banco 2)",
        "P0201" to "Mau funcionamento do circuito do injetor do cilindro 1",
        "P0202" to "Mau funcionamento do circuito do injetor do cilindro 2",
        "P0203" to "Mau funcionamento do circuito do injetor do cilindro 3",
        "P0204" to "Mau funcionamento do circuito do injetor do cilindro 4",
        "P0217" to "Superaquecimento do motor",
        "P0300" to "Falhas de ignição aleatórias ou múltiplos cilindros",
        "P0301" to "Falha de ignição no cilindro 1",
        "P0302" to "Falha de ignição no cilindro 2",
        "P0303" to "Falha de ignição no cilindro 3",
        "P0304" to "Falha de ignição no cilindro 4",
        "P0325" to "Mau funcionamento do sensor de detonação (knock)",
        "P0335" to "Mau funcionamento do sensor de posição do virabrequim (rotação)",
        "P0340" to "Mau funcionamento do sensor de posição do comando de válvulas (fase)",
        "P0351" to "Mau funcionamento do circuito primário/secundário da bobina de ignição A",
        "P0400" to "Mau funcionamento do sistema de recirculação dos gases de escape (EGR)",
        "P0420" to "Eficiência do catalisador abaixo do limite (banco 1)",
        "P0440" to "Mau funcionamento do sistema de controle de emissões evaporativas (EVAP)",
        "P0442" to "Pequeno vazamento no sistema EVAP",
        "P0455" to "Grande vazamento no sistema EVAP (verifique a tampa do tanque)",
        "P0500" to "Mau funcionamento do sensor de velocidade do veículo",
        "P0505" to "Mau funcionamento do sistema de controle de marcha lenta",
        "P0562" to "Tensão do sistema elétrico baixa",
        "P0563" to "Tensão do sistema elétrico alta",
        "P0605" to "Erro de memória da unidade de controle (ECU)",
        "P0700" to "Mau funcionamento do sistema de controle da transmissão",
        "C0035" to "Mau funcionamento do sensor de velocidade da roda dianteira esquerda",
        "C0040" to "Mau funcionamento do sensor de velocidade da roda dianteira direita",
        "C0045" to "Mau funcionamento do sensor de velocidade da roda traseira esquerda",
        "C0050" to "Mau funcionamento do sensor de velocidade da roda traseira direita",
        "C0110" to "Mau funcionamento do motor da bomba do ABS",
        "B0001" to "Falha no circuito do airbag do motorista",
        "B0002" to "Falha no circuito do airbag do passageiro",
        "B0100" to "Falha no circuito de acionamento do pré-tensionador do cinto",
        "U0001" to "Falha de comunicação na rede CAN de alta velocidade",
        "U0100" to "Comunicação perdida com o módulo de controle do motor (ECM/PCM)",
        "U0101" to "Comunicação perdida com o módulo de controle da transmissão (TCM)",
        "U0121" to "Comunicação perdida com o módulo do ABS",
        "U0140" to "Comunicação perdida com o módulo de carroceria (BCM)",
    )

    /** Descrição do [codigo] (ex.: `P0300`); usa um texto genérico pelo sistema se não for conhecido. */
    fun descricaoDe(codigo: String): String {
        val c = codigo.trim().uppercase()
        conhecidos[c]?.let { return it }
        val generico = c.getOrNull(1) == '0' // segundo dígito 0 = código padronizado SAE
        val sistema = when (c.firstOrNull()) {
            'P' -> "trem de força (motor/transmissão)"
            'C' -> "chassi (freios/suspensão/direção)"
            'B' -> "carroceria (airbag/conforto)"
            'U' -> "rede de comunicação entre módulos"
            else -> return "Código desconhecido"
        }
        val tipo = if (generico) "genérico" else "específico do fabricante"
        return "Falha $tipo de $sistema (sem descrição cadastrada)"
    }
}
