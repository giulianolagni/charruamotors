package com.charruamotors.ui.oficina.sensores

import com.charruamotors.CharruaMotorsApp
import com.charruamotors.obd.ModoComunicacaoEcu
import com.charruamotors.obd.pid.DefinicaoPid

/** Sensores que a conexão atual suporta (ECU, montadora e os calculados pelo app). */
fun CharruaMotorsApp.definicoesDaSessao(): List<DefinicaoPid> {
    val suportados = sessaoObd.pidsSuportados.value
    return (sessaoObd.definicoesSuportadas(suportados) + sessaoObd.colunasDoAparelho(suportados)).distinctBy { it.comando }
}

/**
 * Tudo o que o perfil do veículo da sessão prevê, respondendo ou não: o catálogo OBD2 (nos modos
 * 21/22, só os espelhos e os comandos AT) e os PIDs da montadora — nenhum no OBD2 Genérico.
 */
fun CharruaMotorsApp.definicoesDoPerfil(): List<DefinicaoPid> {
    val conexao = sessaoObd.infoConexao.value
    val padraoObd2 = conexao.modo == ModoComunicacaoEcu.OBD2_PADRAO
    val padrao = if (padraoObd2) catalogoPids.pids else conexao.espelhos + catalogoPids.pids.filter { it.ehComandoAt }
    val montadora = catalogoEstendido.perfilPara(sessaoObd.marcaMontadora)?.requisicoes
        ?.filterKeys { padraoObd2 || it.servico == conexao.modo.servico }
        ?.values?.flatten().orEmpty()
    return (definicoesDaSessao() + padrao + montadora).distinctBy { it.comando }
}
