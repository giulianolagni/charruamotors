package com.charruamotors.obd.sessao

import com.charruamotors.obd.DispositivoObd
import java.util.Locale

/**
 * Preferências do modo caixa-preta (aba OBD2 → card "Caixa-preta automática").
 *
 * @param autoConexaoAtiva conectar sozinho ao scanner quando o Bluetooth do carro conectar
 * @param gravarLogsAutomaticos gravar `.txt` + `.csv` em toda conexão (manual ou automática)
 * @param gatilhoNome nome do Bluetooth do carro que dispara a sessão (padrão `Toyota SW4`)
 * @param gatilhoEndereco endereço MAC escolhido na lista de pareados; null = só pelo nome
 * @param scanner último scanner OBD2 (`OBDII`) usado no modo Bluetooth
 * @param veiculoId último veículo aberto na aba OBD2 (0 = nenhum)
 */
data class ConfigAutoConexao(
    val autoConexaoAtiva: Boolean = true,
    val gravarLogsAutomaticos: Boolean = true,
    val gatilhoNome: String = GatilhoBluetooth.NOME_PADRAO,
    val gatilhoEndereco: String? = null,
    val scanner: DispositivoObd? = null,
    val veiculoId: Long = 0,
    val veiculoDescricao: String? = null,
)

/** Evento de link Bluetooth (ACL) recebido pelo `GatilhoBluetoothReceiver`. */
enum class EventoBluetooth { CONECTADO, DESCONECTADO }

/** O que fazer com um evento ACL. */
enum class AcaoGatilho { INICIAR_SESSAO, ENCERRAR_SESSAO, IGNORAR }

/**
 * Reconhecimento do Bluetooth do carro (multimídia/viva-voz) que dispara a sessão automática.
 * A comparação de nomes ignora maiúsculas e espaços (`toyota  sw4 `, `TOYOTA SW4` e `ToyotaSW4`
 * valem para `Toyota SW4`); se o usuário escolheu um aparelho na lista de pareados, o endereço
 * MAC também identifica o carro mesmo que o nome mude.
 */
object GatilhoBluetooth {

    const val NOME_PADRAO = "Toyota SW4"

    private val espacos = Regex("\\s+")

    fun normalizar(nome: String?): String = nome.orEmpty().replace(espacos, "").lowercase(Locale.ROOT)

    fun corresponde(nome: String?, endereco: String?, config: ConfigAutoConexao): Boolean {
        val mac = config.gatilhoEndereco
        if (mac != null && endereco != null && mac.equals(endereco, ignoreCase = true)) return true
        val esperado = normalizar(config.gatilhoNome)
        return esperado.isNotEmpty() && normalizar(nome) == esperado
    }

    /**
     * Decide a reação a um evento ACL:
     * - carro conectou e a auto-conexão está ligada → iniciar a sessão (o serviço ignora se já houver uma);
     * - carro desconectou e a sessão ativa foi aberta por ele → encerrar e salvar os logs
     *   (sessões manuais só terminam pelo usuário ou quando a ECU para de responder);
     * - qualquer outro aparelho (fones, relógio, o próprio scanner OBD) → ignorar.
     */
    fun decidir(
        evento: EventoBluetooth,
        nome: String?,
        endereco: String?,
        config: ConfigAutoConexao,
        sessaoAutomaticaAtiva: Boolean,
    ): AcaoGatilho {
        val scanner = config.scanner?.endereco
        if (scanner != null && endereco != null && scanner.equals(endereco, ignoreCase = true)) return AcaoGatilho.IGNORAR
        if (!corresponde(nome, endereco, config)) return AcaoGatilho.IGNORAR
        return when (evento) {
            EventoBluetooth.CONECTADO -> if (config.autoConexaoAtiva) AcaoGatilho.INICIAR_SESSAO else AcaoGatilho.IGNORAR
            EventoBluetooth.DESCONECTADO -> if (sessaoAutomaticaAtiva) AcaoGatilho.ENCERRAR_SESSAO else AcaoGatilho.IGNORAR
        }
    }
}

/** Escolha do adaptador OBD2 entre os aparelhos pareados (o S24 costuma ter fones, relógio e carro). */
object EscolhaScanner {

    /** Nomes comuns de adaptadores ELM327/OBD2 Bluetooth. */
    private val regexNomeScanner = Regex(
        "obd|elm|v-?link|vgate|icar|konnwei|veepeak|obdlink|carista|viecar|scan",
        RegexOption.IGNORE_CASE,
    )

    fun pareceScannerObd(dispositivo: DispositivoObd): Boolean = regexNomeScanner.containsMatchIn(dispositivo.nome)

    /**
     * Escolha padrão: mantém a [atual] se ainda existir; senão o primeiro com nome de scanner OBD;
     * senão o primeiro da lista (o usuário pode trocar no menu).
     */
    fun escolher(lista: List<DispositivoObd>, atual: DispositivoObd?): DispositivoObd? =
        lista.firstOrNull { it.endereco == atual?.endereco }
            ?: lista.firstOrNull(::pareceScannerObd)
            ?: lista.firstOrNull()

    /**
     * Scanner da sessão automática: o vinculado (último usado), se ainda estiver pareado; senão o
     * primeiro com nome de scanner. Nunca um aparelho qualquer — conectar no rádio do carro não adianta.
     */
    fun paraSessaoAutomatica(pareados: List<DispositivoObd>, vinculado: DispositivoObd?): DispositivoObd? =
        pareados.firstOrNull { vinculado != null && it.endereco.equals(vinculado.endereco, ignoreCase = true) }
            ?: vinculado?.takeIf { pareados.isEmpty() } // lista indisponível (sem permissão de listar): tenta o salvo
            ?: pareados.firstOrNull(::pareceScannerObd)
}
