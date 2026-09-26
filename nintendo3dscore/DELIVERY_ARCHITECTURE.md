# Decisão de entrega do Nintendo 3DS — N3DS-12C2

Status: **REVISADA PARA INSTALL-TIME em 13/09/2026** por decisão do proprietário.
A fronteira do bundle continua separada; o gate físico install-time substitui a
prova histórica on-demand antes do candidato.

## Decisão

O Nintendo 3DS será distribuído como o dynamic feature module
`:nintendo3dscore`, com **Play Feature Delivery no momento da instalação**. A
Google Play entrega o módulo junto com o app, mas ele continua em um split
separado do base. A entrega reúne código, recursos, wrapper JNI e o core ARM64
selado em contêiner autenticado/cifrado como um único limite funcional; não será
usado Play Asset Delivery nem haverá ELF cru do core no split.

A configuração-alvo é:

- plugin `com.android.dynamic-feature`, dependente de `:app`;
- `dist:install-time`, `dist:removable value="true"`, `dist:instant="false"` e
  `dist:fusing include="true"`;
- `minSdk 26`, igual ao app base, e somente `arm64-v8a` nesta primeira versão;
- Activity do feature interna com `android:exported="false"`;
- `SplitCompat` no contexto base e nas Activities do feature;
- app base responsável apenas pelo contrato neutro e pela verificação de que o
  pacote foi instalado integralmente;
- feature responsável por UI 3DS, backend, JNI, contêiner do core, recursos e notices 3DS;
- nenhuma referência estática do app base para classes exclusivas do feature.

`dist:removable="true"` impede que o módulo install-time seja fundido ao base nos
splits servidos aos aparelhos modernos, preservando a fronteira auditável. O app
nunca solicita sua remoção. `dist:fusing="true"` mantém o APK universal gerado
pelo Bundletool como artefato de auditoria; os splits continuam sendo a evidência
primária do comportamento equivalente à Play.

## Evidência e motivação

O core endurecido mede 23.157.736 bytes e o contêiner autenticado/cifrado mede
23.157.804 bytes, além do wrapper, DEX, recursos e notices. Antes da proteção, o
feature comprimia para cerca de 8,25 MB; a medição `N3DS-12F2` do AAB protegido
registrou 23.540.765 bytes comprimidos e 24.201.401 bytes descomprimidos no
feature. A criptografia elimina grande parte da compressão do payload. A decisão
install-time permanece para dar a todos os sistemas o mesmo comportamento,
disponibilidade imediata e uso offline; só nova decisão explícita reabre esse
trade-off.

A documentação Android confirma que feature modules install-time podem separar
código e recursos do base e ser entregues junto com a instalação. O app ainda
deve habilitar `SplitCompat`, confirmar que o módulo está disponível antes de
acessar suas classes e não exportar componentes opcionais. O limite atual
informado para o base gerado do AAB é 500 MB e o total comprimido entregue a um
aparelho é 4 GB; os 23,2 MB do core não exigem Asset Delivery.

Fontes oficiais verificadas e revisitadas em 13/09/2026:

- <https://developer.android.com/guide/playcore/feature-delivery/install-time>
- <https://developer.android.com/guide/playcore/feature-delivery>
- <https://developer.android.com/guide/app-bundle/faq>
- <https://developer.android.com/guide/playcore/asset-delivery>
- <https://developer.android.com/reference/com/google/android/play/core/splitinstall/SplitInstallManager>
- <https://developer.android.com/reference/com/google/android/play/core/splitcompat/SplitCompat>

## Fluxo obrigatório

1. A Google Play instala base e split 3DS na mesma instalação/atualização.
2. O app consulta o conjunto de módulos instalados antes de resolver classes do
   feature; não oferece download, cancelamento, confirmação ou retry do core.
3. Se o pacote estiver incompleto por sideload/restauração defeituosa, a seleção
   orienta atualizar ou reinstalar pela Google Play e não tenta fonte alternativa.
4. Com o split presente, o app reaplica `SplitCompat`, resolve o ponto de entrada
   por contrato neutro e abre o conteúdo sem etapa extra para o usuário.
5. A emulação funciona offline desde a instalação. O app nunca baixa core de
   servidor próprio, nunca envia ROM e nunca ativa cobrança para obter o módulo.
6. Atualizações do feature seguem o mesmo AAB/assinatura e a atualização
   coordenada pela Play; binários avulsos não são aceitos.

O Play Feature Delivery processa metadados do aparelho e versão do aplicativo
para servir e preservar módulos instalados. A revisão de privacidade/Data Safety
é obrigatória em `N3DS-12D/N3DS-14`, antes de distribuição.

## Obrigações de licença

A separação em feature não reduz a obrigação de código correspondente. O
repositório público separado já registrado no plano contém a oferta definida
pelo proprietário e permanece concluído/congelado: esta implementação não o
consulta nem modifica. Licença, notices e o acesso ao link de fonte continuam
disponíveis no app; o feature distribuível retém somente o runtime e o aviso
legal mínimo.

## Alternativas rejeitadas

- **Core no app base:** rejeitado porque perde a fronteira modular/auditável sem
  melhorar a experiência em relação ao feature install-time.
- **Dynamic feature on-demand:** substituído por economizar apenas 8,25 MB no
  download inicial ao custo de um comportamento diferente e internet na primeira
  tentativa de jogar 3DS.
- **Play Asset Delivery:** rejeitado porque asset packs não transportam código
  executável; o core é uma biblioteca nativa.
- **Download binário próprio/CDN:** rejeitado por quebrar a unidade de
  assinatura/atualização do AAB, ampliar a superfície de segurança e complicar
  a entrega do código correspondente.

## Gate de implementação N3DS-12D

`N3DS-12D1` já converteu/anexou o dynamic feature e comprovou no AAB a
separação de DEX, core e wrapper. Em `N3DS-12D3`, o conteúdo distribuível foi
reduzido ao runtime e aviso mínimo; SBOM e código correspondente permanecem
somente no repositório público separado. A identidade do core é validada de
modo fail-closed no build e `scripts/audit-nintendo3ds-bundle.py` impede
payload 3DS no base, SBOM/fonte, ABI/biblioteca extra ou componente exportado.
`N3DS-12D2`
adicionou `SplitCompat` ao contexto base/Activity opcional e um coordenador que
restaura sessões, observa os estados reais da Play, rejeita duplicidade e cobre
cancelamento/retry sem importar classes do feature no base. `N3DS-12D3`
comprovou o ciclo físico Bundletool, inclusive carga nativa e frames reais.

`N3DS-12C2/D4` fechou em 13/09/2026 com:

- [x] o AAB contém `nintendo3dscore` install-time/removível e o base não contém
  core, JNI ou classes exclusivas do feature;
- [x] o módulo ARM64 do AAB contém exatamente o wrapper, o contêiner autenticado
  do core e o aviso legal mínimo, sem ELF cru, SBOM nem pacote de fonte;
- [x] o base usa contrato textual, `SplitCompat` e verificação de disponibilidade,
  sem ação de download do módulo na Biblioteca;
- [x] Bundletool `--local-testing` instala base e feature juntos no Galaxy, sem
  segundo fluxo nem limpeza de dados do app;
- [x] primeira abertura, processo recriado, atualização preservando dados e
  execução offline ficam verdes;
- [x] APK universal de auditoria inclui o feature por `fusing`, mas não é tratado
  como substituto dos testes de splits;
- [x] tamanho comprimido por módulo e variação do base são registrados;
- [x] ROMs, saves e dados privados nunca entram no AAB/APKS nem são limpos nos
  testes.

`N3DS-12F2` repetiu a fronteira no AAB protegido: o auditor recuperou o payload
como um reverser, confirmou o core fixado e os 25 exports, comparou AAB/APK
universal, rejeitou ativos ou ELF residuais e validou assinatura v2/v3. O ciclo
físico de cinco conteúdos de `N3DS-12F3` também passou pelo fluxo de produto no
Galaxy A37, com 106 frames, pausa/retomada, recriação, rotação, cinco
encerramentos e nenhum ELF residual. `N3DS-12F4` fechou o ciclo no mesmo
aparelho: install-time dos splits na versão `900100`, 60 frames e recriação,
update para `900101` preservando a prova privada e nova execução offline. AAB
de update `9e38e7d9...bd0c5`; rede restaurada, APK/pasta QA removidos e matriz de
conteúdos não repetida. `N3DS-12F` está `DONE`.

## Separação do código correspondente

Por decisão do proprietário em 11/09/2026, o pacote de código correspondente
não será incorporado ao AAB/APKS nem mantido como arquivo de fonte dentro do
aplicativo. O repositório público separado
<https://github.com/MateusSouzaAlves/GLP-EmuOrbitAzahar3ds>, revisão
`85ff5ce78e439e5d9fae84dd45165f061a9f18ed`, replica o modelo mínimo do Nintendo
DS: upstream Azahar como submódulo, patches, frontend Android/JNI/Vulkan,
scripts de build/auditoria, licença, notices e SBOM, sem app hospedeiro,
binários, ROMs, firmware, chaves ou saves. A cópia local foi removida somente
após confirmar que a revisão pública era idêntica.

O feature distribuível contém somente o runtime compilado e o aviso legal
mínimo; o auditor rejeita SBOM ou pacote de fonte dentro do bundle. Antes de
cada publicação do app, o repositório separado deve ser sincronizado com o
commit exato do código usado, permanecer acessível sem custo e ser vinculado
claramente ao binário. A publicação do app segue bloqueada até essa revisão ser
amarrada ao candidato e os demais gates de release passarem.
