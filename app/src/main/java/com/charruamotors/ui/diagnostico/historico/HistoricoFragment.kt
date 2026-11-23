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
import com.charruamotors.obd.log.DestinoLogs
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.diagnostico.abastecimentos.AbastecimentosFragment
import com.charruamotors.ui.diagnostico.obd.LeituraObdAdapter
import com.charruamotors.ui.oficina.replay.ArquivoLogCsv
import com.charruamotors.ui.oficina.replay.ReplayLogActivity
import com.charruamotors.ui.util.ArquivoExportado
import com.charruamotors.ui.util.ExportadorCsv
import com.charruamotors.ui.util.definirTexto
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
    private val adapterLogs = ReplayLogActivity.ArquivoAdapter(aoAbrir = ::abrir, aoCompartilhar = ::compartilhar)
    private val adapterViagens = ReplayLogActivity.ArquivoAdapter(aoAbrir = ::abrir, aoCompartilhar = ::compartilhar)
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
            grupoHistorico.addOnButtonCheckedListener { _, id, marcado -> if (marcado) mostrarSecao(id) }
            val inicial = savedInstanceState?.getInt(ESTADO_SECAO) ?: R.id.botao_historico_abastecimentos
            grupoHistorico.check(inicial)
            mostrarSecao(inicial)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.logsCsv.collect {
                        adapterLogs.submitList(it)
                        binding.textoLogsVazio.isVisible = it.isEmpty()
                        binding.tituloLogs.definirTexto(getString(R.string.historico_logs_titulo_qtd, it.size))
                    }
                }
                launch {
                    viewModel.relatoriosViagem.collect {
                        adapterViagens.submitList(it)
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
