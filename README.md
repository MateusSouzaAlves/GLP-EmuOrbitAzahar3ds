# Código-fonte correspondente — Nintendo 3DS

Este repositório contém a fonte, os patches, os avisos e os scripts do
componente Nintendo 3DS distribuído pelo EmuOrbit Advance. A tag
`gpl-source-2026-09-26` preserva o componente anterior. A tag
`gpl-source-2026-10-07-cache-recovery` acrescenta somente a correção do cache
Vulkan para restauração de títulos com ID zero e sua receita/pins. A publicação
da fonte não é publicação de APK nem certificação de toda a interface do app.

ROMs, homebrew, BIOS, firmware, chaves, saves, credenciais, telemetria e
binários pré-compilados não fazem parte deste pacote de fonte.

## Composição da fonte

- `third_party/azahar`: Azahar 2126.0 em
  `fbd3fb02f71e5f9ed5134037fd59bad96c7d2b8a`, incluindo submódulos recursivos;
- `nintendo3dscore`: frontend Android/JNI/Vulkan, feature dinâmica, testes,
  controles e recursos que acompanham o runtime distribuído;
- `nintendo3dscore/patches`, `config` e `scripts`: patches, aquisição,
  auditorias, SBOM e validações de release;
- `app/src/main/cpp/bridge` e `cmake/protected_symbols.cmake`: fontes de
  integração compartilhada exigidas pelo bootstrap;
- [EmuOrbit Advance, tag `gpl-source-2026-09-26`](https://github.com/MateusSouzaAlves/EmuOrbit-Advance/tree/gpl-source-2026-09-26): fonte canônica do host Android e
  da integração final do feature.

O componente combinado é disponibilizado sob **GPL-3.0-or-later**. A licença
e a evidência de cada dependência vinculada estão no SBOM e no índice de
avisos em `nintendo3dscore/compliance`.

## Obter e reproduzir o core

Após clonar, inicialize os upstreams:

```bash
git submodule update --init --recursive
```

Ou obtenha uma cópia limpa do Azahar:

```powershell
.\scripts\acquire-nintendo3ds-source.ps1 -Destination .\third_party\azahar
```

Para construir o core ARM64, use Android NDK `27.3.13750724`, CMake `3.30.3`,
Ninja `1.10.2`, Python 3 e Git:

```powershell
.\scripts\build-nintendo3ds-reference-core.ps1 `
  -AzaharSource .\third_party\azahar `
  -NdkRoot C:\Android\Sdk\ndk\27.3.13750724 `
  -CMakeRoot C:\Android\Sdk\cmake\3.30.3 `
  -Ninja C:\Android\Sdk\cmake\3.30.3\bin\ninja.exe `
  -OutputDirectory .\out
```

A referência auditada é `out/azahar_libretro.so`, com 23.157.736 bytes e
SHA-256 `2ac57376fb096e7e35614eee8cd073bca99804efbe8d7306e49e2de005b57061`.
O registro `referenceBuild` conserva a evidência histórica do core anterior;
`playQualityRecoveryCore` fixa o artefato corrigido e o único patch adicional.
ABI, commit Azahar, dependências, alinhamento de 16 KiB e hardening permanecem.
Seis cápsulas do core anterior foram restauradas em processos separados com
120 frames novos em testes privados; não certificam partidas completas ou
todas as versões históricas de estado. O APK/AAB normal continua sujeito
aos gates de release do projeto. Nenhuma mídia/estado/credencial de teste
é publicada nesta atualização, nem código novo de telas, Room ou analytics.

## Fonte correspondente e publicação

`CORRESPONDING_SOURCE.json` identifica a composição e a tag de fonte. Antes
de distribuir um novo AAB, atualize a fonte, publique uma tag, registre a URL,
tag e hashes no repositório do app e preserve o acesso público enquanto o
binário correspondente estiver disponível. A ausência de uma ROM ou de um
binário pré-compilado neste repositório não limita os direitos concedidos pela
GPL sobre o software aqui documentado.
