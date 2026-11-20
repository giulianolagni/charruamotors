package com.charruamotors.domain.model

/**
 * Tipos de serviço de manutenção. O Room grava o nome da constante no banco.
 * Os intervalos (km e meses) são usados para prever a próxima manutenção;
 * `null` significa que o tipo não tem periodicidade fixa.
 */
enum class TipoManutencao(val intervaloKm: Int?, val intervaloMeses: Int?) {
    TROCA_OLEO(10_000, 12),
    FILTROS(10_000, 12),
    FREIOS(20_000, 24),
    PNEUS(40_000, 48),
    REVISAO(10_000, 12),
    ELETRICA(null, null),
    OUTROS(null, null),
}
