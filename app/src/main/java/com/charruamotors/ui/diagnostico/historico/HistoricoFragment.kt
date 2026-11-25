package com.charruamotors.ui.diagnostico.historico

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.FragmentHistoricoBinding
import com.charruamotors.obd.aprendizado.ResultadoExclusao
import com.charruamotors.obd.log.DestinoLogs
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.diagnostico.abastecimentos.AbastecimentosFragment
import com.charruamotors.ui.diagnostico.obd.LeituraObdAdapter
import com.charruamotors.ui.oficina.replay.ArquivoLogCsv
import com.charruamotors.ui.oficina.replay.ReplayLogActivity
import com.charruamotors.ui.util.ArquivoExportado
import com.charruamotors.ui.util.ExportadorCsv
import com.charruamotors.ui.util.definirTexto
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Aba Histórico: unifica os **abastecimentos** (consumo médio e custo por km), os **logs salvos**
 * (CSV do Data Logger e da caixa-preta, abertos no replay) e o **diário de viagens** (relatório
 * `.txt` de cada sessão automática e as leituras salvas no Painel). Toque longo compartilha o arquivo.
 */
class HistoricoFragment : Fragment() {

    private var _binding: FragmentHistoricoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HistoricoViewModel by viewModels { HistoricoViewModel.Factory }
    private val adapterLogs = ReplayLogActivity.ArquivoAdapter(aoAbrir = ::abrir, aoCompartilhar = ::acoesDoArquivo)
    private val adapterViagens = ReplayLogActivity.ArquivoAdapter(aoAbrir = ::abrir, aoCompartilhar = ::acoesDoArquivo)
    private val adapterLeituras = LeituraObdAdapter()

    private val app: CharruaMotorsApp get() = requireActivity().application as CharruaMotorsApp

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoricoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(R.id.container_abastecimentos) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.container_abastecimentos, AbastecimentosFragment.novo(requireArguments().getLong(DiagnosticoActivity.EXTRA_VEICULO_ID)))
                .commit()
        }
        with(binding) {
            listaLogs.adapter = adapterLogs
            listaViagens.adapter = adapterViagens
            listaLeituras.adapter = adapterLeituras
            botaoAbrirVisualizador.setOnClickListener { startActivity(ReplayLogActivity.intent(requireContext())) }
            botaoLimparLogs.setOnClickListener { limparLogs() }
            botaoLimparViagens.setOnClickListener { limparLogs() }
            grupoHistorico.addOnButtonCheckedListener { _, id, marcado -> if (marcado) mostrarSecao(id) }
            val inicial = savedInstanceState?.getInt(ESTADO_SECAO) ?: R.id.botao_historico_abastecimentos
            grupoHistorico.check(inicial)
            mostrarSecao(inicial)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.logsCsv.collect {
                        adapterLogs.definirArquivos(it)
                        binding.textoLogsVazio.isVisible = it.isEmpty()
                        binding.tituloLogs.definirTexto(getString(R.string.historico_logs_titulo_qtd, it.size))
                    }
                }
                launch {
                    viewModel.relatoriosViagem.collect {
                        adapterViagens.definirArquivos(it)
                        binding.textoViagensVazio.isVisible = it.isEmpty()
                        binding.tituloViagens.definirTexto(getString(R.string.historico_viagens_titulo_qtd, it.size))
                    }
                }
                launch {
                    viewModel.leituras.collect {
                        adapterLeituras.submitList(it)
                        binding.textoLeiturasVazio.isVisible = it.isEmpty()
                    }
                }
                launch { app.preferenciasApp.config.collect { adapterLeituras.definirConversor(it.conversor) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Volta do replay ou de uma sessão automática encerrada: pode haver log novo.
        viewModel.atualizar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let { outState.putInt(ESTADO_SECAO, it.grupoHistorico.checkedButtonId) }
    }

    private fun mostrarSecao(id: Int) = with(binding) {
        containerAbastecimentos.isVisible = id == R.id.botao_historico_abastecimentos
        painelLogs.isVisible = id == R.id.botao_historico_logs
        painelViagens.isVisible = id == R.id.botao_historico_viagens
    }

    private fun abrir(arquivo: ArquivoLogCsv) = startActivity(ReplayLogActivity.intent(requireContext(), arquivo))

    /** Toque longo: compartilhar ou apagar o arquivo. */
    private fun acoesDoArquivo(arquivo: ArquivoLogCsv) {
        val opcoes = arrayOf(getString(R.string.historico_acao_compartilhar), getString(R.string.historico_acao_apagar))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(arquivo.nome)
            .setItems(opcoes) { _, qual -> if (qual == 0) compartilhar(arquivo) else confirmarApagar(arquivo) }
            .show()
    }

    private fun confirmarApagar(arquivo: ArquivoLogCsv) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.historico_apagar_titulo)
            .setMessage(getString(R.string.historico_apagar_mensagem, arquivo.nome))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.historico_limpar_confirmar) { _, _ -> viewModel.apagar(arquivo, ::avisarExclusao) }
            .show()
    }

    /** "Limpar logs": escolhe entre os do veículo atual e todos, com confirmação antes de apagar. */
    private fun limparLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            val (doVeiculo, todos) = viewModel.contagensParaLimpar()
            if (todos == 0) {
                Snackbar.make(binding.root, R.string.historico_limpar_vazio, Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            var escolhido = 0
            val opcoes = arrayOf(getString(R.string.historico_limpar_veiculo, doVeiculo), getString(R.string.historico_limpar_todos, todos))
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.historico_limpar_titulo)
                .setSingleChoiceItems(opcoes, escolhido) { _, qual -> escolhido = qual }
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.historico_limpar_confirmar) { _, _ -> viewModel.limpar(todos = escolhido == 1, ::avisarExclusao) }
                .show()
        }
    }

    private fun avisarExclusao(r: ResultadoExclusao) {
        val b = _binding ?: return
        val texto = if (r.falhas.isEmpty()) {
            getString(R.string.historico_apagado_ok, r.apagados.size)
        } else {
            getString(R.string.historico_apagado_falhas, r.apagados.size, r.falhas.size)
        }
        Snackbar.make(b.root, texto, Snackbar.LENGTH_LONG).show()
    }

    private fun compartilhar(arquivo: ArquivoLogCsv) {
        val mime = if (arquivo.texto) DestinoLogs.MIME_TXT else DestinoLogs.MIME_CSV
        val exportado = ArquivoExportado(viewModel.uriCompartilhavel(arquivo), arquivo.nome, "")
        runCatching { startActivity(ExportadorCsv.intentCompartilhar(exportado, getString(R.string.replay_compartilhar_titulo), mime)) }
            .onFailure { Snackbar.make(binding.root, it.message ?: "", Snackbar.LENGTH_LONG).show() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ESTADO_SECAO = "secao_historico"

        fun novo(veiculoId: Long) = HistoricoFragment().apply {
            arguments = Bundle().apply { putLong(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId) }
        }
    }
}
