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

O app tem quatro Activities, todas com o mesmo tema Material 3 e a mesma Toolbar. A navegação segue o fluxo `Login → Dashboard → (Formulário de veículo | Diagnóstico)`. Entre as telas só trafega o id do veículo; cada tela recarrega os dados do banco.

### 1. Login (`LoginActivity`)

- Login simulado, sem backend: as credenciais são conferidas na tabela `usuarios` do Room. O usuário padrão é criado na primeira tentativa de login.
- Validação de e-mail e de senha (mínimo de 6 caracteres), com o erro exibido no próprio campo.
- A sessão fica salva em `SharedPreferences`: com sessão ativa, o app abre direto no Dashboard.

### 2. Dashboard (`DashboardActivity`)

- Lista dos veículos cadastrados (`RecyclerView`), cada um com um resumo:
  - quilometragem atual e combustível;
  - consumo médio em km/l, ou "Sem dados de consumo" quando ainda não há dois tanques cheios;
  - próxima manutenção prevista, destacada em vermelho quando vencida;
  - alerta de códigos de falha (DTCs) ativos no histórico OBD2.
- O resumo se atualiza sozinho a cada cadastro, manutenção, abastecimento ou leitura de DTCs, porque combina os `Flow`s dos repositórios.
- Botão "Novo veículo", atalho "Conectar scanner OBD2" (pergunta qual veículo quando há mais de um) e logout na Toolbar, que limpa a pilha de telas.

### 3. Formulário de veículos (`VeiculoFormActivity`): CRUD completo

| Operação | Como |
|---|---|
| **Create** | Aberta sem `EXTRA_VEICULO_ID` |
| **Read** | Aberta com `EXTRA_VEICULO_ID`: carrega os dados do veículo |
| **Update** | Salvar alterações do veículo existente |
| **Delete** | Ação na Toolbar, com confirmação. Apaga em cascata manutenções, peças, abastecimentos, leituras e DTCs |

Campos: marca, modelo, ano, placa, cor, km atual, tipo de combustível e capacidade do tanque.

### 4. Diagnóstico e histórico (`DiagnosticoActivity`)

Três abas com `TabLayout` + `ViewPager2`, cada uma um Fragment:

- **Diagnóstico OBD2:** escolha entre scanner simulado e Bluetooth real, seleção do dispositivo pareado, conexão, dados em tempo real a cada 1 s (RPM, velocidade, temperatura do motor, carga do motor e tensão da bateria), gravação da leitura no histórico, leitura dos códigos de falha (modo `03`) com descrição em português e limpeza (modo `04`) com confirmação.
- **Manutenções:** histórico com edição e exclusão. O cadastro abre em BottomSheet: tipo de serviço, data, km, custo, oficina, descrição e lista dinâmica de peças trocadas (nome, quantidade e valor). No topo da lista aparece a previsão das próximas manutenções.
- **Abastecimentos:** histórico com edição e exclusão. O cadastro abre em BottomSheet: data, km, litros, valor, combustível e tanque cheio. No topo da lista ficam o consumo médio e o custo por km.

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
- O `ObdViewModel` mantém a conexão durante a rotação de tela.
- O socket é fechado em `onCleared()`, quando a tela é encerrada de vez.
- O estado da conexão é exposto como `StateFlow<EstadoConexaoObd>`: `Desconectado`, `Conectando`, `Conectado` ou `Erro(mensagem)`.

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
├── obd/                     # ObdClient, Elm327BluetoothClient, ObdClientFake, ObdParser, DescricaoDtc
└── ui/
    ├── login/               # LoginActivity + LoginViewModel
    ├── dashboard/           # DashboardActivity, VeiculoAdapter, DashboardViewModel
    ├── veiculo/             # VeiculoFormActivity + VeiculoFormViewModel
    ├── diagnostico/         # DiagnosticoActivity, PagerAdapter e as abas:
    │   ├── obd/             #   ObdFragment, ObdViewModel, CodigoFalhaAdapter
    │   ├── manutencoes/     #   ManutencoesFragment, ManutencaoSheet, adapter, ViewModel
    │   └── abastecimentos/  #   AbastecimentosFragment, AbastecimentoSheet, adapter, ViewModel
    └── util/                # Formatadores, insets, seletor de data, permissões Bluetooth, rótulos
```

Recursos em `app/src/main/res`:

- `layout/`: telas, itens de lista e BottomSheets;
- `values/`: `strings.xml`, `colors.xml`, `dimens.xml` e `themes.xml`;
- `menu/`: menus das Toolbars.

---

## Testes unitários

São **88 testes** JUnit em `app/src/test`, todos sem dependência do Android. Rode com `./gradlew test`.

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
| `ConvertersTest` | 3 | Conversão de `LocalDate`/`LocalDateTime` para o Room |

---

## Fluxo de trabalho no Git

- Todo o trabalho é feito em branches a partir da `main` (`feature/...`, `fix/...`, `docs/...`, `chore/...`).
- As branches são integradas com `git merge --no-ff`, o que preserva no histórico a evidência de cada uma.
- Os commits são pequenos e seguem o padrão Conventional Commits em português, ex.: `feat: cria aba de abastecimentos`.
