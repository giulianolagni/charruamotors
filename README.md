# Charrua Motors

Aplicativo Android para **controle de manutenção veicular**, **gestão de abastecimentos** e **diagnóstico automotivo via OBD2**. O proprietário acompanha o histórico de serviços, os custos de rodagem e a saúde de cada carro. Os dados de injeção eletrônica são lidos direto da ECU por um scanner Bluetooth ELM327.

Projeto acadêmico, desenvolvido em Kotlin com layouts XML, arquitetura MVVM e persistência local com Room.

## Integrantes da dupla

| Nome | Matrícula |
|---|---|
| Giuliano Chiochetta Lagni | 199805 |
| Hugo Pizzatto | 193213 |

Disciplina: Desenvolvimento Mobile II. Professor: Jaqson.

---

## Sumário

- [Integrantes da dupla](#integrantes-da-dupla)
- [Proposta](#proposta)
- [Tecnologias utilizadas](#tecnologias-utilizadas)
- [Prints das telas](#prints-das-telas)
- [Funcionalidades](#funcionalidades)
- [Diferencial: integração OBD2](#diferencial-integração-obd2)
- [Direção: Spotify, Waze e Modo HUD (v1.1)](#direção-spotify-waze-e-modo-hud-v11)
- [Pacote Oficina (v1.2)](#pacote-oficina-v12)
- [Menu lateral, Configurações e Assistente (v1.3)](#menu-lateral-configurações-e-assistente-v13)
- [Abas, personalização do Painel e unidades globais (v1.4)](#abas-personalização-do-painel-e-unidades-globais-v14)
- [Veículo favorito, navegação limpa e Painel livre (v1.5)](#veículo-favorito-navegação-limpa-e-painel-livre-v15)
- [GitHub Releases, atualizador e sensores da SW4 (v1.5)](#github-releases-atualizador-e-sensores-da-sw4-v15)
- [Modo Flex, faixas esperadas, Cruze 1.4T e Teste de Atuadores (v2.1)](#modo-flex-faixas-esperadas-cruze-14t-e-teste-de-atuadores-v21)
- [Regras de negócio e cálculos](#regras-de-negócio-e-cálculos)
- [Arquitetura e stack](#arquitetura-e-stack)
- [Como executar](#como-executar)
- [Estrutura de pacotes](#estrutura-de-pacotes)
- [Testes unitários](#testes-unitários)
- [Fluxo de trabalho no Git](#fluxo-de-trabalho-no-git)

---

## Proposta

Muitos proprietários de veículos não têm um registro organizado de manutenções, abastecimentos e falhas do carro. O Charrua Motors reúne esse histórico em um só app, calcula consumo médio, custo por km e próximas manutenções, e lê dados e códigos de falha da ECU por um scanner Bluetooth OBD2 (ELM327). Sem scanner, o modo simulado permite demonstrar tudo no emulador.

## Tecnologias utilizadas

Kotlin, Android Studio, layouts XML com View Binding e Material Components 3, Activities e Fragments (`TabLayout` + `ViewPager2`), MVVM com `ViewModel`, Kotlin Coroutines e `Flow`, Room (SQLite), `SharedPreferences`, Bluetooth Clássico (SPP/RFCOMM) e Gradle Kotlin DSL com KSP. Detalhes em [Arquitetura e stack](#arquitetura-e-stack).

## Prints das telas

Os prints das telas do aplicativo estão em [docs/prints_telas.md](docs/prints_telas.md) (imagens em [docs/prints/](docs/prints/)).

---

## Funcionalidades

O app tem quatro Activities principais, todas com o mesmo tema Material 3 e a mesma Toolbar. Desde a v1.5 a navegação segue o fluxo `Login → abas do veículo favorito` (ou `Login → Meus Veículos` sem veículo cadastrado), com Meus Veículos e o formulário abertos por cima das abas. Entre as telas só trafega o id do veículo; cada tela recarrega os dados do banco.

### 1. Login (`LoginActivity`)

- Login simulado, sem backend: as credenciais são conferidas na tabela `usuarios` do Room. O usuário padrão é criado na primeira tentativa de login.
- Validação de e-mail e de senha (mínimo de 6 caracteres), com o erro exibido no próprio campo.
- A sessão fica salva em `SharedPreferences`: com sessão ativa, o app abre direto nas abas do **veículo favorito** (aba Painel); sem veículo cadastrado, em Meus Veículos.

### 2. Meus Veículos (`DashboardActivity`)

- Lista dos veículos cadastrados (`RecyclerView`), cada um com um resumo:
  - quilometragem atual e combustível;
  - consumo médio em km/l, ou "Sem dados de consumo" quando ainda não há dois tanques cheios;
  - próxima manutenção prevista, destacada em vermelho quando vencida;
  - alerta de códigos de falha (DTCs) ativos no histórico OBD2.
- O resumo se atualiza sozinho a cada cadastro, manutenção, abastecimento ou leitura de DTCs, porque combina os `Flow`s dos repositórios.
- Estrela do **veículo favorito** em cada card (o app abre direto nele), botão "Abrir Painel" e "Novo veículo".
- Aberta pelo menu lateral ou pelo seletor da Toolbar das abas, tem a seta de Voltar; como tela inicial (sem veículo), tem o menu lateral — ver [v1.5](#veículo-favorito-navegação-limpa-e-painel-livre-v15).

### 3. Formulário de veículos (`VeiculoFormActivity`): CRUD completo

| Operação | Como |
|---|---|
| **Create** | Aberta sem `EXTRA_VEICULO_ID` |
| **Read** | Aberta com `EXTRA_VEICULO_ID`: carrega os dados do veículo |
| **Update** | Salvar alterações do veículo existente |
| **Delete** | Ação na Toolbar, com confirmação. Apaga em cascata manutenções, peças, abastecimentos, leituras e DTCs |

Campos: marca, modelo, ano, placa, cor, km atual, tipo de combustível e capacidade do tanque.

### 4. Tela do veículo (`DiagnosticoActivity`)

Desde a v1.4, quatro abas na navegação inferior (`BottomNavigationView` + `ViewPager2` sem deslizar), cada uma um Fragment:

- **Painel:** card de conexão inteligente (início rápido Bluetooth/Simulado ou barra compacta conectada) e os blocos escolhidos pelo usuário — Músicas, Navegação, Mostradores (1 a 12), gráfico ao vivo, computador de bordo, cronômetro 0–100, lista rápida de sensores e informações da ECU.
- **Oficina:** cards grandes para Teste de Bateria, Diagnóstico de Erros (DTCs), Varredura de PIDs e Terminal ELM327.
- **Manutenções:** histórico com edição e exclusão. O cadastro abre em BottomSheet: tipo de serviço, data, km, custo, oficina, descrição e lista dinâmica de peças trocadas (nome, quantidade e valor). No topo da lista aparece a previsão das próximas manutenções.
- **Histórico:** abastecimentos (consumo médio, custo por km e CRUD em BottomSheet), logs CSV/TXT salvos e o diário de viagens.

---

## Diferencial: integração OBD2

A comunicação com o scanner fica atrás da interface `ObdClient`, com duas implementações. O ViewModel não sabe qual delas está em uso.

```
ObdViewModel ──> ObdClient (interface)
                   ├── Elm327BluetoothClient   scanner ELM327 real
                   └── ObdClientFake           simulação para o emulador
```

### Scanner real: `Elm327BluetoothClient`

- **Bluetooth Clássico (SPP):** socket RFCOMM com o UUID `00001101-0000-1000-8000-00805F9B34FB`, escolhendo entre os dispositivos já pareados.
- **Cascata de conexão em 3 camadas**, dirigida por `assets/cascata_conexao.json`:
  1. **OBD2 padrão:** `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATSP0` e os bitmasks `0100`/`0120`/`0140`.
  2. **Fallback de protocolo físico:** se o `0100` do `ATSP0` der `UNABLE TO CONNECT`/`NO DATA`, o app força `ATSP6` (CAN 11 bits), `ATSP7` (CAN 29 bits), `ATSP5` (KWP Fast Init) e `ATSP3` (ISO 9141). Isso contorna clones ELM327 v2.1 que falham no modo automático.
  3. **Fallback de serviço:** se a ECU recusar o modo 01 (`7F 01 11`), o app envia `ATSH 7E0` uma única vez e testa o **modo 21** (Toyota M-OBD, Mitsubishi, Suzuki, KWP: `210C`, `2105`...). Se ele não responder, testa o **modo 22 UDS** (VW/Audi, Fiat/Jeep, Ford, GM: `22F40C`...). A marca cadastrada decide qual dos dois vai primeiro.
- **Mapeamento semântico:** cada PID de `pids_padrao.json` tem uma `chave` (`RPM`, `VELOCIDADE`, `MAP`, `TEMP_MOTOR`...). Os PIDs dos modos 21/22 publicam o valor na mesma chave, então mostradores, gráfico, computador de bordo, 0–100, log CSV e filas de polling funcionam igual, venha o dado de `01xx`, `21xx` ou `22F4xx`. O card da ECU mostra a camada ativa e quantos sensores responderam.
- **DTCs:** `03`/`07`/`0A`/`04` no OBD2 padrão; `13` (com fallback `18 00 FF 00`) e `14`/`14 FF 00` no modo 21; `19 02 FF` e `14 FF FF FF` no modo 22. Todos são decodificados no padrão SAE J2012 (ex.: `P0300`).
- **Baixa latência:**
  - sufixo de resposta única (`010C1`, `210C1`, `22F40C1`): o ELM327 responde assim que a primeira ECU fala. É testado na conexão (`01001` no OBD2 padrão, o primeiro PID que respondeu nos modos 21/22) e desligado se o adaptador recusar;
  - `ATAT2` depois do `ATSP0` e `ATST0C` (~49 ms de espera em vez de 200 ms) com a ECU já achada, validado relendo um PID. DTCs, VIN e limpeza voltam ao `ATST32` antes de rodar. Se o clone responder `?`, o app segue no timing padrão;
  - sem pausa fixa entre comandos (o prompt `>` já indica o fim da resposta). Depois de um timeout ou `STOPPED`, a sessão passa a usar 15 ms de respiro;
  - telemetria em duas filas: RPM, MAP/turbo e velocidade (mais o MAF fora do diesel) em todo ciclo, e 1 PID lento a cada 3 ciclos;
  - ponteiros animados em 90 ms, com interpolação linear.
- **Estabilidade no v2.1:** o `ATSH` só é enviado quando o cabeçalho muda (o ciclo de polling é agrupado por cabeçalho).
- **Robustez:**
  - toda a I/O roda em `Dispatchers.IO`;
  - um `Mutex` serializa os comandos;
  - há timeout por comando;
  - respostas `NO DATA`, `?` e `UNABLE TO CONNECT` são tratadas;
  - a queda de conexão vira `EstadoConexaoObd.Erro`, sem travar a UI.
- **Permissões:** `BLUETOOTH_CONNECT` e `BLUETOOTH_SCAN` pedidas em tempo de execução no Android 12+. `BLUETOOTH` e `BLUETOOTH_ADMIN` usam `maxSdkVersion="30"`. O app também pede para ligar o Bluetooth antes de conectar.

O parser das respostas (`ObdParser`) é um objeto Kotlin puro, sem Android, e tolera:

- eco do comando, prompt `>` e `SEARCHING...`;
- respostas sem espaços;
- respostas multi-frame CAN e de várias ECUs.

### Modo simulado: `ObdClientFake`

Permite desenvolver e apresentar o app **no emulador, sem hardware**:

- simula um carro em trânsito: a velocidade acelera e freia, o RPM acompanha as marchas, o motor aquece até ~90 °C e o alternador mantém ~14 V;
- traz códigos de falha de exemplo (`P0300`, `P0171`, `P0420`) para demonstrar leitura e limpeza;
- simula a latência de conexão (~1,2 s).

O modo é escolhido na própria aba OBD2. O padrão inicial fica em `CharruaMotorsApp.modoObd` e vem como `SIMULADO`.

### Ciclo de vida da conexão

- O polling de PIDs só roda com a aba visível: começa em `onStart`/`onResume` e pausa em `onPause`/`onStop`.
  A exceção é o Modo HUD: com o painel flutuante aberto, o polling continua com o app minimizado (ver abaixo).
- O `ObdViewModel` mantém a conexão durante a rotação de tela.
- O socket é fechado em `onCleared()`, quando a tela é encerrada de vez.
- O estado da conexão é exposto como `StateFlow<EstadoConexaoObd>`: `Desconectado`, `Conectando`, `Conectado` ou `Erro(mensagem)`.

---

## Direção: Spotify, Waze e Modo HUD (v1.1)

Card **Direção** no topo da aba OBD2 (pacote `direcao/`):

- **Mini-player nativo** (`ControladorMidia` + `MidiaNotificacaoService`): acha o app de música ativo via
  `MediaSessionManager` (Spotify tocando tem prioridade, depois qualquer player tocando, depois o Spotify
  pausado) e mostra capa, faixa e artista com os botões Anterior, Play/Pause e Próxima. Tocar na capa ou no
  título abre o player. Sem o **Acesso a notificações** liberado, aparece só o botão discreto
  "Ativar controle de música". Em APK instalado fora da Play Store, o Android pode marcar essa opção como
  *configuração restrita*: libere em Informações do app › ⋮ › Permitir configurações restritas.
- **Atalhos do Waze** (`AtalhosWaze` / `AbridorWaze`): abrir o Waze, posto mais próximo
  (`waze://?q=Posto%20de%20combustivel&navigate=yes`) e oficina mecânica, com fallback para a Play Store.
- **Modo HUD (sobre Waze)** (`HudFlutuanteService`): serviço em primeiro plano `connectedDevice` com uma
  janela `TYPE_APPLICATION_OVERLAY` arrastável e semitransparente, mostrando RPM, MAP/turbo (kPa), temperatura
  do motor (°C) e pressão do rail (bar) ou, sem rail, a tensão da bateria. Acima de 105 °C o painel fica
  vermelho. Ele tem botões para alternar para o Waze e para fechar o HUD e voltar ao app. Os dados chegam
  pela `TelemetriaCompartilhada` do Application. Enquanto o HUD está aberto, o `ObdViewModel` mantém o
  polling mesmo com a Activity em segundo plano (`ObdViewModel.devePollar`).

Ao ativar, o app pede em sequência: "Aparecer sobre outros apps", Bluetooth (pré-requisito do serviço
`connectedDevice` no Android 14+) e, opcionalmente, notificações (para ver o "Fechar HUD" na gaveta).

---

## Pacote Oficina (v1.2)

Card **Ferramentas de Oficina** na aba OBD2, logo abaixo do card do scanner. Cada ferramenta abre numa
tela própria; a lógica de cada uma fica no pacote puro `oficina/` (testável na JVM) e as telas em `ui/oficina/`.

**Sessão manual sem derrubar o Bluetooth.** O Teste de Bateria e o Terminal abrem uma *sessão manual* no
`ObdClient` (`iniciarSessaoManual` / `enviarComandoManual` / `encerrarSessaoManual`):

- o polling pausa: a aba OBD2 sai de cena (`onPause`/`onStop`) e os fluxos `lerTelemetria`/`lerDadosEmTempoReal`
  ficam suspensos entre ciclos enquanto a sessão estiver ativa, inclusive com o HUD flutuante aberto. Um
  `Mutex` de ciclo garante que a sessão só começa depois que o ciclo em andamento termina, então nenhum `ATSH`
  do polling cai no meio dos comandos manuais;
- os comandos manuais vão crus, sem `ATSH` automático. Quando o usuário envia `ATSH`/`ATST`, o cliente
  passa a considerar esse cabeçalho/timeout como o ativo. Um `ATZ`, um `ATH1` ou um `LV RESET` marcam a
  configuração para ser reaplicada;
- ao fechar a tela, o cliente reaplica `ATE0 ATL0 ATS0 ATH0` (e `ATAT2`) se algo mudou e devolve ao
  adaptador o cabeçalho que estava ativo na entrada. O polling retoma no mesmo socket;
- o modo monitor (`ATMA`/`ATMR`/`ATMT`) e o `ATLP` são recusados, porque travariam a sessão.

### 1. Teste de Bateria e Alternador (`TesteBateriaActivity`)

Tela guiada em 3 etapas. Ela lê `ATRV` na taxa máxima do adaptador (piso de 50 ms, ~20 amostras/s) e mostra a
tensão num gráfico em `Canvas` (`GraficoTensaoView`, janela de 20 s com as linhas de referência da etapa). A
máquina de estados `TesteBateria` é pura:

| Etapa | Captura | Classificação |
|---|---|---|
| 1. Repouso (ignição ligada, motor desligado) | 5 s; vale a média da 2ª metade (tensão estabilizada) | Saudável ≥ 12,4 V · Carga baixa 12,0–12,3 V · Crítica < 12,0 V |
| 2. Partida (cranking) | espera a queda (≥ 0,8 V ou um `LV RESET`), guarda o vale `V_min` e termina 1 s depois de o motor pegar (> 13,2 V) | OK ≥ 10,0 V · Atenção 9,6–9,9 V · Fraca < 9,6 V |
| 3. Alternador | 8 s em marcha lenta e 10 s a ~2.500 RPM com cargas: média, pico e ripple estimado | Subcarga < 13,5 V · Normal 13,6–14,6 V · Sobrecarga > 14,8 V |

- O `LV RESET` (o ELM327 reiniciando por subtensão na partida) conta como amostra perdida e nunca aborta o
  teste. O laudo avisa que o vale real pode ter sido menor que o registrado.
- Etapas podem ser puladas ou refeitas. Sem queda de tensão em 20 s, a etapa 2 pede para repetir.
- O **Laudo Resumido** (`LaudoBateria`) traz o veredito da bateria e do alternador e os valores medidos, com
  botões Copiar e Compartilhar.
- No modo simulado, o `ObdClientFake` acompanha as fases. Em repouso a tensão fica em ~12,5 V, com a carga
  superficial se dissipando. Na partida cai para um vale de ~10,3 V, com um `LV RESET` e as oscilações das
  compressões. Com o motor ligado, o alternador fica em ~14,2 V.

### 2. Visualizador de Logs CSV (`ReplayLogActivity`)

- Lista os `.csv` de `Downloads/CharruaOBD`: no Android 10+ pelo `MediaStore`, que mostra os logs criados
  pelo próprio app sem pedir permissão; no Android 8–9, pela pasta do app. O botão **Abrir outro CSV** usa o
  Storage Access Framework (`ACTION_OPEN_DOCUMENT`).
- `LeitorCsvLog` lê o cabeçalho dinamicamente (`Nome (unidade) [comando]`; `timestamp_ms`/`data_hora`/`tempo_s`
  são as colunas de tempo) em streaming. Ele aceita RFC 4180 com BOM e também o CSV reexportado pelo Excel
  pt-BR (`;` e vírgula decimal).
- `LogReplayChartView` é um `Canvas` sem bibliotecas. Mostra até 3 sensores, escolhidos por chips, cada um
  com cor própria e escala normalizada no mínimo/máximo da janela. Gestos:
  - pinça para zoom (`ScaleGestureDetector`) e arraste da linha do tempo (`GestureDetector`);
  - toque para posicionar o cursor vertical (toque longo e arraste para movê-lo) e toque duplo para ver tudo.

  Com mais pontos que pixels, cada coluna desenha o mínimo e o máximo do trecho, então os picos não somem.
- Abaixo do gráfico aparecem o horário exato do cursor, o valor de cada sensor nele e o resumo
  **Mínimo / Máximo / Média** da janela visível (`AnaliseReplay`).

### 3. Terminal ELM327 e Varredura de PIDs (`TerminalObdActivity`)

- **Terminal:** comandos brutos (`ATDP`, `ATSH 7E0`, `2101`, `13`...) e atalhos (`ATDP`, `ATRV`,
  `ATSH 7E0/7E1/7DF`...). O log monoespaçado mostra o comando, a resposta bruta (um quadro por linha) e a
  latência em ms, com opção de copiar.
- **Varredura de PIDs (engenharia reversa):** cabeçalho (`7E0`, `7E1`, `7E2`, `7DF`), serviço (`21`, `01`, `22`,
  com DIDs de 4 dígitos) e faixa hex. Há presets **Toyota Motor (7E0 · 2100–21FF)** e **Toyota Câmbio
  (7E1 · 2100–21FF)**, além de OBD2 padrão e UDS.
  - A varredura roda em sequência, com barra de progresso e botão Parar. O Parar é atendido entre comandos,
    para não deixar resposta atrasada no canal. A varredura também para sozinha depois de 5 timeouts seguidos.
  - Um filtro oculta `NO DATA`/`7F`/erros, com opção de mostrar tudo.
  - Cada PID que respondeu aparece com seus bytes hexadecimais brutos, inclusive multi-frame CAN.
  - O relatório pode ser copiado ou compartilhado (útil para mapear a temperatura do ATF e a correção de
    injetores da Hilux SW4, por exemplo).
- No modo simulado, uma `EcuSimulada` responde como uma Hilux: motor no `7E0` e câmbio no `7E1` pelo
  serviço 21, `7F 21 31` fora dos blocos, nenhum módulo no `7E2` e multi-frame acima de 7 bytes.

---

## Menu lateral, Configurações e Assistente (v1.3)

**Menu lateral (`DrawerLayout` + `NavigationView` no Dashboard).** O cabeçalho mostra o usuário logado, o
veículo ativo (`Toyota SW4 2013`) e uma bolinha verde/cinza com o status da sessão OBD2 (`Conectado · Gravando log`
ou `Em espera`), ao vivo a partir da `SessaoObd`. Itens:

- **Navegação rápida:** Meus Veículos (troca rápida do veículo ativo, com atalho para a aba OBD2 dele), Logs e
  Relatórios (Visualizador de Logs `.csv`/`.txt`) e Ferramentas de Oficina (Teste de Bateria/Alternador,
  Terminal/Varredura de PIDs e Replay; as duas primeiras pedem o scanner conectado);
- **Sistema & App:** Configurações, Checklist de Permissões e Backup e Restauração;
- **Rodapé:** Sobre o App (`Charrua OBD Pro v1.3`, `versionCode`, tamanho dos catálogos e protocolo atual) e
  Sair, com confirmação. O logout limpa só a sessão de login: banco local, logs e preferências ficam.

**Configurações (`ConfiguracoesActivity`).** A configuração da caixa-preta saiu da aba OBD2 (que agora só tem
uma faixa discreta `Caixa-preta ativa · Gatilho: Toyota SW4` que abre esta tela):

- *Caixa-preta automática:* auto-conexão em segundo plano, logs automáticos (`.txt` + `.csv`), Bluetooth
  gatilho do carro, scanner OBD2 padrão, veículo padrão e o atalho de bateria "Sem restrições";
- *Aparência e Painel:* tema `Seguir sistema` / `Claro` / `Escuro` (`AppCompatDelegate.setDefaultNightMode`,
  aplicado na hora e na inicialização do app; cores do escuro em `values-night/`) e unidade de pressão do
  Turbo/MAP em `kPa`, `bar` ou `psi` — vale para o mostrador principal, a grade de PIDs (todo PID em kPa) e o
  HUD flutuante. Os logs continuam em kPa;
- *Assistente:* "Refazer Assistente Inicial" e atalho para o Checklist.

**Assistente de Primeira Abertura (`AssistenteActivity`).** Abre sozinho no primeiro Dashboard depois do login
(flag em `PreferenciasApp`, separada da sessão de login) e tem "Pular" em todos os passos:

1. permissões essenciais: Bluetooth (`BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN`), notificações e bateria "Sem restrições";
2. scanner e gatilho: lista os pareados, pré-seleciona o `OBDII` e o `Toyota SW4` (ou deixa digitar o nome);
3. veículo padrão, com auto-conexão e logs automáticos já ligados;
4. recursos de direção opcionais: Acesso a Notificações (Spotify) e Sobrepor apps (HUD), com status verde/pendente.

**Checklist de Permissões (`ChecklistPermissoesActivity`).** Status em tempo real (verde OK / vermelho Pendente,
relido no `onResume`) e ação direta para cada item. As regras por versão do Android (Bluetooth em tempo de
execução no 12+, notificações no 13+) ficam em `sistema/permissoes/ChecklistPermissoes`, lógica pura.

**Backup e Restauração.** Exporta para um `.json` escolhido pelo usuário (`ACTION_CREATE_DOCUMENT`, sem
permissão de armazenamento) todas as tabelas ligadas aos veículos — veículos, manutenções e peças,
abastecimentos, leituras e códigos de falha, com os IDs originais — e as preferências da caixa-preta e da
aparência. A restauração (`ACTION_OPEN_DOCUMENT`) valida o arquivo antes (tipo, versão do formato, enums e
referências entre tabelas), mostra um resumo para confirmar e troca tudo numa única transação do Room
(`BackupDao.substituirTudo`): ou restaura tudo, ou nada muda. Os usuários do login simulado não entram no backup.

---

## Abas, personalização do Painel e unidades globais (v1.4)

**Quatro abas na navegação inferior.** A tela do veículo virou Painel · Oficina · Manutenções · Histórico. O
Painel fica só com o que se usa dirigindo; as ferramentas de diagnóstico ganharam a aba Oficina e os registros
foram reunidos no Histórico.

**Personalizar Painel (`PersonalizarPainelActivity`, botão no topo da aba Painel).** Tudo é salvo na hora em
`PreferenciasPainel` (arquivo `painel`) e a aba se redesenha sozinha:

- *ordem do topo* por arrastar e soltar (`ItemTouchHelper`, pela alça): Músicas (Spotify), Navegação (atalhos do
  Waze e Modo HUD) e Marcadores (sensores);
- *estilo dos mostradores* do `GaugeView`, com prévia ao vivo: **Esportivo** (ponteiro em arco), **Digital
  Moderno** (números grandes e anel de progresso) e **Fita/Off-road** (barra linear, mais baixa que larga);
- *os 4 relógios principais*: qualquer um dos 14 sensores de `SensorPainel` (RPM, velocidade, turbo, temperaturas
  do motor/admissão/óleo, carga, borboleta, acelerador, bateria, common rail, MAF, tanque e consumo), cada um com a
  escala da unidade de exibição; escolher um sensor que já está em outro relógio troca os dois;
- *lista rápida*: os PIDs que aparecem na grade (nenhum marcado = todos os suportados; "Ver todos" no Painel);
- *HUD flutuante* e "Restaurar padrão".

**HUD flutuante customizável.** Ao tocar em Modo HUD, uma tela de ativação pergunta o tamanho — *Minimizado* (só
RPM e Turbo, painel mais estreito) ou *Expandido* (até 4 sensores fixados) — e o estilo: Números, Números grandes
ou Com barra (fração da escala). `FormatadorHud` monta as células (lógica pura) e o `HudFlutuanteService` as cria
dinamicamente. O Rail sem leitura cai para a tensão da bateria e o alerta vermelho continua acima de 105 °C.

**Aba Oficina (`OficinaFragment`).** Cards grandes e ilustrados, com o status do scanner no topo:
🔋 Teste de Bateria (guiado, com laudo), 🔍 Diagnóstico de Erros (`DiagnosticoErrosActivity`: confirmadas,
pendentes, permanentes, Freeze Frame e limpeza com confirmação), 📡 Varredura de PIDs e 💻 Terminal ELM327.

**Varredura amigável.** `IdentificadorPids` cruza cada resposta com os três catálogos do app — PIDs padrão do modo
01, espelhos dos modos 21/22 da cascata (`210C` = RPM no `7E0`) e perfis de montadora (`2182` = ATF no `7E1`) — e
decodifica o valor pela fórmula do catálogo, já na unidade global. A lista mostra, em tempo real,
`Identificado: Rotação do motor (PID 210C) · 850 rpm`, `Desconhecido (PID 21D4 - resposta: 61D4...)` ou
`Sem resposta`, com uma barra colorida (`BarraVarreduraView`: identificados, desconhecidos, ignorados e o que
falta), as métricas `X sensores encontrados · Y identificados · Z ignorados` e o filtro rápido
**"Ver apenas o que a SW4 respondeu"** (o modelo do veículo). O relatório inclui o nome e o valor dos identificados.

**Terminal didático.** Área de ajuda com sugestões (`Consulte a bateria com ATRV`, `Altere o foco com ATSH 7E0`,
`Leia o RPM com 210C`), atalhos de um toque (`ATRV`, `ATDP`, `ATSH 7E0`, `ATSH 7E1`, `210C`, `0902`, `010C`, `03`,
`ATSH 7DF`) e, para cada linha do log, a descrição do comando e a resposta traduzida ao lado do hex bruto
(`TradutorTerminal`: tensão com diagnóstico, protocolo, foco do `ATSH`, VIN, falhas, PIDs padrão e de montadora no
contexto do `ATSH` ativo, erros do ELM327 e respostas negativas `7F`). O botão **Laudo** (`LaudoTerminal`) gera o
resumo da sessão, as leituras traduzidas e o log bruto para copiar/compartilhar.

**Histórico (`HistoricoFragment`).** Abastecimentos, Logs (CSV do Data Logger e da caixa-preta, abertos direto no
replay; toque longo compartilha) e Viagens (relatórios `.txt` de cada sessão da caixa-preta e as leituras salvas).

**Unidades globais (Configurações → Aparência e Painel).** Métrico (km/h, °C, kPa ou bar, km/L) ou Imperial (mph,
°F, psi, MPG do galão americano, milhas e galões). `ConversorUnidades` (puro) converte pela unidade do catálogo e é
aplicado na hora nos mostradores e suas escalas, na lista rápida, no computador de bordo, no cronômetro, no Freeze
Frame, nas leituras salvas, na varredura, no Terminal e no HUD. No imperial a pressão é sempre psi; logs `.csv`/`.txt`
e o banco continuam no SI do OBD2.

**Ícone 1.4.** Espora gaúcha cromada integrada com um plug OBD2 de 16 pinos, arco de LEDs azuis (como um
conta-giros) e silhueta off-road sobre fibra de carbono, em adaptive icon com camada monocromática (ícones temáticos
do Android 13+). É desenhado por código: `docs/icone/IconeCharrua.java` (Java2D) gera todas as densidades.

---

## Veículo favorito, navegação limpa e Painel livre (v1.5)

**Abertura direta no veículo favorito.** O favorito é o veículo padrão já salvo (o mesmo da caixa-preta) ou, sem ele,
o primeiro cadastrado (`VeiculoFavorito`, puro). Com o login feito e algum veículo, o app entra direto nas 4 abas
desse veículo, na aba Painel; sem veículo, abre Meus Veículos e, ao cadastrar o primeiro, o Painel dele. Em Meus
Veículos a estrela de cada card troca o favorito. Na Toolbar, o nome do veículo ativo (com `★ Favorito`) abre o seletor
rápido de veículos ou Meus Veículos.

**Menu lateral persistente e Voltar correto.** O `DrawerLayout` agora vive na tela das abas (`MenuLateralGlobal`): os 3
riscos ficam visíveis em Painel, Oficina, Manutenções e Histórico. O menu tem só ações globais (`AcaoMenuLateral`):
Meus Veículos (Trocar / Favorito), Configurações, Checklist de Permissões, Backup e Restauração, Sobre e Sair. As telas
abertas por ele entram por cima da atual, sem flags de pilha nem `parentActivityName`: a seta ou o gesto de Voltar
retornam exatamente para a aba de onde o usuário saiu. Só o logout limpa a pilha.

**Cada função em um único lugar.**

| Onde | O que fica lá |
|---|---|
| Aba Painel | Card de conexão, Modo HUD (no bloco Navegação), Personalizar Painel (ícone na Toolbar) e os blocos escolhidos |
| Aba Oficina | Teste de Bateria, Diagnóstico de Erros (DTCs), Varredura de PIDs e Terminal ELM327 |
| Aba Manutenções | Manutenções, peças e lembretes |
| Aba Histórico | Abastecimentos, logs CSV/TXT e viagens |
| Menu lateral | Meus Veículos, Configurações, Checklist, Backup, Sobre e Sair |

Saíram do Painel o atalho de DTCs e a faixa da caixa-preta (que já tem lugar nas Configurações); saíram do menu os
atalhos de Logs e Ferramentas de Oficina (já são abas).

**Card de conexão inteligente.** Desconectado: "Conectar no Carro (Bluetooth)", com o adaptador salvo (ex.: `OBDII`) e
o ícone para trocar o pareado, e "Modo Simulado" com 1 toque. Ao conectar (manual ou pela caixa-preta), o card recolhe
para a barra `● Conectado · OBDII · 7E0` com Desconectar (`StatusConexaoPainel`, puro).

**Personalizar Painel 2.0.**

- **Blocos:** todos os 8 blocos do Painel são arrastáveis pela alça (`≡`) e têm o botão de remover; os removidos ficam
  em "Blocos removidos (+ Adicionar de volta)". No bloco Navegação, cada botão (Waze, Posto, Oficina, Modo HUD) pode ser
  escondido.
- **Mostradores:** lista dinâmica de 1 a 12 relógios (começa com os 4 padrão), cada um com alça, sensor, estilo próprio
  (Esportivo, Digital ou Fita) e remover, mais "+ Adicionar mostrador" e "Aplicar estilo a todos". No Painel, grade de 2
  colunas; a Fita ocupa a largura total (`GradeMostradores`), com altura contida e todos os rótulos que couberem, sem
  números encostados entre relógios vizinhos.
- **Lista rápida e HUD:** os sensores escolhidos são reordenados por arraste.
- **Backup:** o `.json` leva a personalização do Painel (`CodecPainel`, as mesmas chaves das preferências) e as unidades
  globais. Backups antigos sem esses campos restauram sem mexer neles; as preferências da v1.4 migram sozinhas (o estilo
  global vira o estilo de cada relógio).

---

## GitHub Releases, atualizador e sensores da SW4 (v1.5)

**APKs fora do Git.** O `dist/*.apk` saiu do repositório (`.gitignore`). O APK de release assinado continua sendo gerado
em `dist/CharruaOBDPro-<versão>-release.apk` (e copiado para `G:\Meu Drive\CharruaOBD`, se existir), mas é publicado
como anexo da GitHub Release `v<versão>` pelo `scripts/publicar-release.ps1`:

```powershell
.\scripts\publicar-release.ps1            # assembleRelease + gh release create v1.5 (ou upload --clobber se já existir)
.\scripts\publicar-release.ps1 -SemBuild  # só publica o APK que já está em dist/
```

O script exige o GitHub CLI autenticado (`winget install --id GitHub.cli` e `gh auth login`). Mudanças grandes sobem
`versionCode` e `versionName`; ajustes pequenos mantêm a versão e só substituem o APK da release.

**Atualizador automático e manual** (`sistema/atualizacao`, `ui/atualizacao/AtualizadorApp`):

- Na abertura da tela principal, o `VerificadorAtualizacao` consulta `releases/latest` da API do GitHub em
  `Dispatchers.IO` e compara a tag com o `BuildConfig.VERSION_NAME` (`VersaoSemantica`, `major.minor.patch` com
  pré-release). Sem internet, `404`/`401` (repositório privado ou sem release) ou já atualizado: nada aparece. Com
  versão nova: diálogo com a versão, as notas da release e **Baixar e instalar** / **Depois** (o "Depois" vale para a
  sessão).
- **Verificar atualizações** no Sobre o App e nas Configurações: responde "Você já está na última versão" ou, no
  `404/401`, oferece **Abrir Releases no GitHub**.
- **Baixar e instalar**: o APK vai para `cacheDir/updates/` com barra de progresso; sem a permissão "Instalar apps
  desconhecidos" (`REQUEST_INSTALL_PACKAGES`), o app abre a tela do sistema e continua a instalação na volta; o
  instalador nativo recebe o APK pelo `FileProvider` (`ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`).

**Sensores estendidos da Toyota SW4** (perfil Toyota de `pids_estendidos.json`, sondados na conexão; só entra o que o
módulo responder dentro da faixa):

| Sensor | Requisição | No Painel |
|---|---|---|
| Pedal do acelerador APP D / APP E (%) | `7E0 · 2149` / `7E0 · 214A` | "Pedal Acelerador" (usa o `0149` ou, sem ele, o `2149`); separado da Borboleta (`2111`/`0111`) |
| Marcha engatada (N, 1ª–6ª) | `7E1 · 2130` (ou `7E0 · 2130`) | "Marcha" |
| Alavanca (P R N D 3 2 L) | `7E1 · 21D9` (ou `7E0 · 21D9`) | "Marcha / Alavanca" |
| Temperatura do ATF (°C) | `7E1 · 2182` (ou `7E0 · 2182`) | "Câmbio ATF" |
| Tração 4x4 / bloqueio | `7C0 · 2101` (provisório) | "Tração 4x4" |
| VSC / estabilidade | `7B0 · 2103` (provisório) | "VSC / Estabilidade" |

Sensores de estado mostram o rótulo (`D`, `4x4 (H4)`, `VSC OFF`) no mostrador, no HUD e na Lista Rápida (o campo
opcional `rotulos` do catálogo). Sem resposta do módulo, o mostrador fica em "—" e a Lista Rápida o esconde. Módulos
fora de `7E0`–`7E7` respondem no endereço + 8 (ABS `7B8`, painel `7C8`), fora do filtro padrão do ELM327: o cliente
ajusta o `ATCRA` junto do `ATSH` e volta ao padrão no motor (e desiste se o clone recusar o comando).

**Varredura multi-módulo** (aba Oficina → Varredura de PIDs): presets **Toyota ABS / VSC / Estabilidade (`7B0`)**,
**Toyota Painel / 4WD / Carroceria (`7C0`)** e **Varredura Completa SW4 (7E0 + 7E1 + 7B0 + 7C0)**, que testa os 4
cabeçalhos em sequência (pulando cedo o módulo ausente) e gera um relatório único, com uma seção por módulo e os bytes
brutos de cada identificador, para copiar e decodificar marcha, alavanca, 4x4 e VSC na SW4 real. No modo simulado, a
ECU da Hilux responde também ao pedal (`2149`/`214A`), à marcha/alavanca e aos módulos `7B0`/`7C0`.


## Modo Flex, faixas esperadas, Cruze 1.4T e Teste de Atuadores (v2.1)

**Modo Flex Automático e A/F** (`domain/calculo/CombustivelFlex.kt`). No cadastro o combustível é **Diesel**,
**Gasolina**, **Etanol** ou **Auto Flex**. No Auto Flex o app lê o teor de etanol (`0152`) a cada ciclo, classifica o
tanque (**Gasolina E27** abaixo de 40 %, **Mistura Flex** de 40 a 85 %, **Etanol E100** a partir de 85 %) e ajusta a
estequiometria de referência por interpolação linear (14,7:1 no E0, **13,2:1 no E27**, **9,0:1 no E100**). O consumo via
MAF e o computador de bordo usam essa relação. A **Relação A/F Real** é λ medido (sonda de banda larga `0124`/`0134`;
sem ela, o λ comandado `0144`) × a estequiometria, calculada pela sessão em cada instantâneo (`CALC_AFR`, também
gravada no CSV). Mostradores novos no Painel, Cluster e HUD (grupo *Mistura & Flex*): **Sonda Lambda 1 (V)** `0114`,
**Sonda Lambda 1 (λ)**, **Relação A/F Real (:1)**, **Teor de Etanol (%)**, **STFT**/**LTFT** e **Avanço de Ignição**.

**Faixas mínimas e máximas por veículo** (`domain/faixas`). Cada veículo tem um conjunto de faixas esperadas: o
preset reconhecido pela marca/modelo/ano ou, sem preset, as genéricas montadas a partir dos limites de alerta do
cadastro. As faixas aparecem na ficha do veículo (cadastro → *Ficha técnica · Faixas esperadas*), deixam vermelho o
mostrador que sair delas (o RPM de marcha lenta só vale parado e sem acelerar; a tensão, só com o motor ligado) e
entram no relatório `.txt` da sessão (lido mín.–máx. e % das leituras fora). Preset **Toyota Hilux SW4 3.0 D-4D 2013
(1KD-FTV)**: lenta 650–750 rpm, máx. seguro 4.200 rpm, água 80–98 °C, ATF 60–105 °C, bateria/alternador 13,4–14,6 V,
Common Rail 280–1.800 bar (lenta 280–380 bar), turbo (MAP) 95–215 kPa e bicos #1–#4 entre −3,0 e +3,0 mm³/st.

**Perfil Chevrolet / GM e Cruze 1.4 Turbo Flex 2018 (LE2)**. Preset com `ATSP6` (ISO 15765-4 CAN 11 bits 500 kbps),
ECU do motor `7E0` e TCM `7E1`. O motor usa o OBD2 padrão (RPM, velocidade ECU + GPS, MAP/turbo, MAF, temperaturas,
carga, borboleta, rail GDI `0123`, avanço `010E`, sonda lambda, etanol `0152`, STFT/LTFT, tensão `0142`). A temperatura
do ATF vem por UDS `22 1940` no TCM `7E1` e, na alternativa, no `7E2`; o módulo que não responder fica fora do polling
sem derrubar a sessão. No **Modo Simulado**, um veículo Chevrolet/GM liga a simulação do Cruze (`SimuladorCruze`):
turbo com vácuo na lenta e ~230 kPa em plena carga, câmbio de 6 marchas, mistura E48 (ajustável no Teste de
Atuadores), sonda de banda larga com corte na desaceleração e o ATF do TCM.

**Teste de Atuadores** (aba Oficina, `oficina/atuadores` + `TesteAtuadoresActivity`). Abre uma sessão manual (o
polling fica pausado sem fechar o Bluetooth), fixa `ATSH 7E0`, entra na sessão estendida `10 03` e a mantém com
`3E 00` a cada 2 s (também durante pulsos longos). Testes: bomba de combustível, eletroventilador (baixa e alta),
embreagem do A/C, válvula do cânister (EVAP), pulso dos bicos #1–#4 e das bobinas #1–#4, além de um comando `2F`
customizado. Cada teste envia `2F <DID> 03 <estado>` e sempre devolve o controle com `2F <DID> 00`, inclusive ao tocar
em Parar. Os itens críticos (bomba, A/C, bicos, bobinas e o comando customizado) só são liberados com RPM = 0. Uma
recusa da central vira uma mensagem técnica com o NRC (ex.: `7F 2F 33` Seed/Key, `7F 2F 11` serviço não suportado,
`7F 2F 31` DID fora da faixa) e a sessão continua utilizável. A tabela GM traz **DIDs de referência didática**
(numeração ilustrativa `01xx`, não confirmada em central real): confirme os identificadores no GDS2/Tech2 antes de usar
no carro. No Modo Simulado a ECU do Cruze responde aos atuadores, as bobinas pedem Seed/Key e a SW4 recusa o serviço,
para demonstrar os NRCs em sala de aula; a chave *Simular motor ligado* mostra a trava de RPM.

---

## Regras de negócio e cálculos

Validações e cálculos ficam na camada de domínio (`domain/`), sem dependência do Android, e são cobertos por testes unitários. A UI apenas exibe os resultados.

### Validações

| Formulário | Regras |
|---|---|
| Login | E-mail válido; senha com no mínimo 6 caracteres; mensagem para credenciais inválidas |
| Veículo | Marca, modelo, ano e placa obrigatórios; placa no padrão antigo (`AAA-9999`) ou Mercosul (`AAA9A99`), normalizada em maiúsculas e **única**; ano entre 1900 e o ano atual + 1; km não negativa |
| Manutenção | Data não futura; km não negativa e não menor que a do último registro; custo maior que zero; peças com nome, quantidade e valor válidos |
| Abastecimento | Data não futura; km não menor que a do último registro; litros e valor maiores que zero; litros não maiores que a capacidade do tanque |

Os campos numéricos aceitam vírgula ou ponto como separador decimal. Um registro com km maior que a atual também atualiza a km do veículo.

### Cálculos (`CalculosVeiculo`)

- **Consumo médio (km/l):** usa o método do tanque cheio. É a distância entre o primeiro e o último tanque cheio dividida pelos litros colocados nesse trecho, contando também os abastecimentos parciais.
- **Custo por km:** soma abastecimentos e manutenções e divide pelos km rodados entre o menor e o maior registro.
- **Gasto por período:** total de abastecimentos e manutenções entre duas datas.
- **Próxima manutenção:** prevista a partir da última realizada de cada tipo. A mais urgente vem primeiro e as vencidas são marcadas. Os intervalos usados:

| Tipo | Intervalo de km | Intervalo de tempo |
|---|---|---|
| Troca de óleo | 10.000 km | 12 meses |
| Filtros | 10.000 km | 12 meses |
| Revisão | 10.000 km | 12 meses |
| Freios | 20.000 km | 24 meses |
| Pneus | 40.000 km | 48 meses |
| Elétrica / Outros | sem periodicidade | sem periodicidade |

---

## Arquitetura e stack

```
Activity / Fragment  ──>  ViewModel  ──>  Repository  ──>  DAO (Room)
   (View Binding)        (StateFlow)                   └─>  ObdClient (Bluetooth / simulado)
```

| Camada | Tecnologia |
|---|---|
| Linguagem | Kotlin 2.3 |
| Interface | Layouts XML (View system) + View Binding + Material Components 3 (`MaterialToolbar`, `TextInputLayout`, `BottomSheetDialogFragment`, `TabLayout`, `ViewPager2`) |
| Arquitetura | MVVM, com `ViewModel` + `SavedStateHandle` para preservar estado em mudanças de configuração |
| Assíncrono | Kotlin Coroutines (`viewModelScope`, `lifecycleScope`, `repeatOnLifecycle`) e `Flow`/`StateFlow` |
| Persistência | Room 2.8 (SQLite), schema na versão 3, com chaves estrangeiras `CASCADE` e `TypeConverters` para datas |
| Sessão | `SharedPreferences` |
| Build | Gradle Kotlin DSL com version catalog (`gradle/libs.versions.toml`) e KSP para o Room |

Os schemas do banco são exportados em [app/schemas/](app/schemas/).

---

## Como executar

### Requisitos

- Android Studio em versão compatível com o **Android Gradle Plugin 9.0**
- **JDK 17**
- Android SDK Platform **37** (`compileSdk`/`targetSdk` 37)
- Emulador ou aparelho com **Android 8.0 (API 26)** ou superior

Para usar o scanner real é preciso também:

- um aparelho físico com Bluetooth;
- um adaptador **ELM327 Bluetooth Clássico** já pareado nas configurações do Android.

O emulador não tem Bluetooth, então lá use o modo simulado.

### Passo a passo

1. Clone o repositório e abra a pasta do projeto no Android Studio.
2. Aguarde a sincronização do Gradle.
3. Execute a configuração `app` em um emulador ou aparelho.
4. Entre com o usuário padrão:

   | E-mail | Senha |
   |---|---|
   | `admin@charruamotors.com` | `123456` |

### Linha de comando

```bash
./gradlew assembleDebug          # gera o APK de debug
./gradlew test                   # roda os testes unitários
./gradlew connectedAndroidTest   # testes instrumentados (exige emulador/aparelho)
```

No Windows (cmd ou PowerShell), use `gradlew.bat` no lugar de `./gradlew`.

### Roteiro de demonstração

1. Faça login e cadastre um veículo (teste a validação com uma placa inválida ou já existente).
2. Toque em **Diagnóstico** no card do veículo, deixe o modo **Simulado** e conecte. Os dados em tempo real começam a chegar.
3. Leia os códigos de falha: o Dashboard passa a mostrar o alerta de DTCs ativos. Limpe-os e o alerta some.
4. Registre dois abastecimentos de tanque cheio para ver o consumo médio, e uma troca de óleo para ver a próxima manutenção prevista.

---

## Estrutura de pacotes

```
app/src/main/java/com/charruamotors/
├── CharruaMotorsApp.kt      # Application: banco, repositórios, sessão e cliente OBD2
├── data/
│   ├── local/               # AppDatabase, Converters e DAOs do Room
│   ├── repository/          # Veiculo, Manutencao, Abastecimento, Diagnostico e Usuario
│   └── session/             # SessaoManager (SharedPreferences)
├── domain/
│   ├── model/               # Entidades e enums: Veiculo, Manutencao, PecaTrocada, Abastecimento,
│   │                        # LeituraObd, CodigoFalha, TipoCombustivel, TipoManutencao,
│   │                        # EstadoConexaoObd (sealed)...
│   ├── calculo/             # CalculosVeiculo, ProximaManutencao, ResumoVeiculo
│   └── validacao/           # Validadores de login, veículo, manutenção, peça e abastecimento
├── direcao/                 # Card de direção (v1.1):
│   ├── midia/               #   ControladorMidia, MidiaNotificacaoService, EstadoMidia (lógica pura)
│   ├── waze/                #   AtalhosWaze (deep links, puro) e AbridorWaze
│   ├── hud/                 #   HudFlutuanteService, FormatadorHud/PainelHud, TelemetriaCompartilhada
│   └── painel/              #   ConfigPainel (v1.5: blocos, Mostrador com estilo próprio), CodecPainel,
│                            #   GradeMostradores, SensorPainel e PreferenciasPainel
├── obd/                     # ObdClient, Elm327BluetoothClient, ObdClientFake (+ EcuSimulada), ObdParser
├── sistema/                 # Lógica pura: StatusCaixaPreta, PreSelecaoAssistente e StatusConexaoPainel,
│   ├── navegacao/           #   v1.5: VeiculoFavorito, DestinoInicial e AcaoMenuLateral
│   ├── preferencias/        #   PreferenciasApp (tema, unidade, flag do assistente), TemaApp
│   ├── unidades/            #   UnidadePressao e ConversorUnidades (métrico ↔ imperial)
│   ├── permissoes/          #   ChecklistPermissoes (regras por versão) e LeitorPermissoes
│   └── backup/              #   BackupJson (serialização/validação) e BackupRepository (SAF)
├── oficina/                 # Pacote Oficina (v1.2), lógica pura:
│   ├── bateria/             #   TesteBateria (máquina de estados), ClassificadorBateria, LaudoBateria
│   ├── replay/              #   LeitorCsvLog, AnaliseReplay (estatísticas, normalização, zoom/arraste)
│   ├── varredura/           #   VarreduraPids, RelatorioVarredura e IdentificadorPids (varredura amigável)
│   └── terminal/            #   FormatadorTerminal, TradutorTerminal e LaudoTerminal
└── ui/
    ├── login/               # LoginActivity + LoginViewModel
    ├── dashboard/           # DashboardActivity, VeiculoAdapter, DashboardViewModel
    ├── veiculo/             # VeiculoFormActivity + VeiculoFormViewModel
    ├── diagnostico/         # DiagnosticoActivity (navegação inferior), PagerAdapter e as abas:
    │   ├── obd/             #   Painel: ObdFragment, ObdViewModel, SensorAdapter
    │   ├── oficina/         #   OficinaFragment (cards das ferramentas)
    │   ├── manutencoes/     #   ManutencoesFragment, ManutencaoSheet, adapter, ViewModel
    │   ├── historico/       #   HistoricoFragment e HistoricoViewModel
    │   └── abastecimentos/  #   AbastecimentosFragment (dentro do Histórico), AbastecimentoSheet, ViewModel
    ├── navegacao/           # MenuLateralGlobal (3 riscos das telas principais, backup, sobre e logout)
    ├── painel/              # PersonalizarPainelActivity, adapters arrastáveis, MontadorMostradores e EditorConfigHud
    ├── oficina/             # Telas da Oficina: bateria/, falhas/, replay/ e terminal/
    ├── assistente/          # AssistenteActivity (Assistente de Primeira Abertura)
    ├── configuracoes/       # ConfiguracoesActivity e DialogosCaixaPreta
    ├── permissoes/          # ChecklistPermissoesActivity, PainelPermissoes, SolicitadorPermissoes
    ├── componentes/         # GaugeView (3 estilos), BarraVarreduraView, TelemetriaChartView, GraficoTensaoView, LogReplayChartView
    └── util/                # Formatadores, insets, seletor de data, permissões Bluetooth, rótulos
```

Recursos em `app/src/main/res`:

- `layout/`: telas, itens de lista e BottomSheets;
- `values/`: `strings*.xml`, `colors.xml`, `dimens.xml` e `themes.xml` (DayNight); `values-night/`: cores do tema escuro;
- `menu/`: menu lateral global, navegação inferior (`menu_abas`) e menus das Toolbars;
- `mipmap-*`: ícone adaptativo (fundo, frente e monocromático) e versões legadas, gerados por `docs/icone/IconeCharrua.java`.

---

## Testes unitários

São **681 testes** JUnit em `app/src/test`, todos rodando na JVM. Rode com `./gradlew test`. Alguns destaques:

| Classe de teste | Testes | O que cobre |
|---|---:|---|
| `ObdParserTest` | 29 | Interpretação das respostas do ELM327: PIDs, tensão, DTCs (CAN e não CAN), `NO DATA`, `?`, eco, multi-frame e lixo |
| `CalculosVeiculoTest` | 14 | Consumo médio, custo por km, gasto por período, próxima manutenção e resumo do Dashboard |
| `ObdClientFakeTest` | 12 | Estados de conexão, leituras simuladas, leitura e limpeza de DTCs, falha simulada |
| `ValidadorAbastecimentoTest` | 8 | Data futura, km mínima, litros e valor, capacidade do tanque |
| `ValidadorVeiculoTest` | 7 | Campos obrigatórios, placa antiga e Mercosul, ano, km e tanque |
| `ValidadorLoginTest` | 5 | Formato do e-mail e tamanho da senha |
| `ValidadorManutencaoTest` | 5 | Data, km mínima e custo |
| `ValidadorPecaTest` | 5 | Nome, quantidade e valor das peças |
| `EstadoMidiaTest` | 11 | Escolha do player (prioridade do Spotify), estados de reprodução e textos do mini-player |
| `PainelHudTest` | 10 | HUD minimizado/expandido, sensores fixados (até 4, sem repetir), estilo com barra, rail ou tensão, imperial, alerta acima de 105 °C e a ponte de telemetria |
| `ConversorUnidadesTest` | 11 | Métrico ↔ imperial de velocidade, temperatura, pressão, consumo (MPG), distância, volume e vazão; escalas dos mostradores e persistência |
| `ConfigPainelTest` | 10 | Ordem do Painel (arrastar e soltar, normalização), troca de relógios, lista rápida e persistência das preferências do Painel e do HUD |
| `IdentificadorPidsTest` | 11 | Varredura amigável com os catálogos reais: `210C`/`2123` identificados, desconhecidos, ignorados, perfil Toyota, imperial, métricas e relatório |
| `TradutorTerminalTest` | 10 | Atalhos e dicas, descrição dos comandos, tradução das respostas (bateria, VIN, PIDs, `7F`, erros), cabeçalho ativo e o laudo do Terminal |
| `AtalhosWazeTest` | 5 | Deep links do Waze (posto, oficina), codificação da busca e fallback para a Play Store |
| `ConvertersTest` | 3 | Conversão de `LocalDate`/`LocalDateTime` para o Room |
| `TesteBateriaTest` | 19 | Faixas de repouso/partida/alternador, máquina de estados das 3 etapas, `LV RESET` na partida, partida não detectada e laudo |
| `SessaoManualTest` | 14 | Pausa e retomada do polling, comando bruto sem `ATSH` automático, restauração do `ATSH`/configuração, curva de bateria e ECU Hilux do simulado |
| `VarreduraPidsTest` | 13 | Gerador de faixas (serviços 21/01/22), validação, classificação (positiva, multi-frame, `7F`, `NO DATA`, timeout), filtro, Parar e relatório |
| `LeitorCsvLogTest` | 11 | CSV do Data Logger (ida e volta), Excel pt-BR, aspas, BOM, linhas malformadas e ordenação |
| `AnaliseReplayTest` | 11 | Estatísticas da janela, normalização por série, zoom com foco, arraste nos limites, cursor e seleção de até 3 sensores |
| `FormatadorTerminalTest` | 4 | Log do terminal com latência, timeout e erros |
| `BackupJsonTest` | 13 | Ida e volta do backup `.json` (todas as tabelas e preferências), nulos, arquivo inválido, de outro app, formato mais novo, enum desconhecido e registros órfãos |
| `UnidadePressaoTest` | 9 | Conversão kPa → bar / psi, casas decimais, escala do mostrador e o Turbo/MAP do HUD na unidade escolhida |
| `ChecklistPermissoesTest` | 8 | Status verde/vermelho por versão do Android (Bluetooth no 12+, notificações no 13+), essenciais e recursos de direção |
| `PreferenciasAppTest` | 8 | Persistência do Assistente Inicial (primeira abertura, concluir, refazer), tema e unidade |
| `VersaoSemanticaTest` | 6 | Tags `v1.5`/`1.6.0-beta`, comparação numérica por parte (`1.10` > `1.9`) e pré-release antes da final |
| `InterpretadorReleaseGithubTest` | 10 | JSON da API de Releases (APK, notas, `null`), `404/401` de repositório privado, `403`, JSON quebrado e sem internet |
| `SensoresSw4Test` | 10 | Pedal `2149`/`214A` em %, marcha/alavanca/4x4/VSC com rótulos, `ATCRA`, mostradores com PID alternativo e o simulador |
| `VarreduraMultiModuloTest` | 10 | Presets `7B0`/`7C0`, Varredura Completa SW4, `ATCRA`, módulo ausente, conexão perdida e o relatório consolidado |
| `StatusCaixaPretaTest` | 8 | Cabeçalho do menu lateral, faixa da aba OBD2 e pré-seleção do `OBDII` e do `Toyota SW4` no assistente |

---

## Fluxo de trabalho no Git

- Todo o trabalho é feito em branches a partir da `main` (`feature/...`, `fix/...`, `docs/...`, `chore/...`).
- As branches são integradas com `git merge --no-ff`, o que preserva no histórico a evidência de cada uma.
- Os commits são pequenos e seguem o padrão Conventional Commits em português, ex.: `feat: cria aba de abastecimentos`.
