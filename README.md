# Código-fonte correspondente — Nintendo 3DS

Este repositório contém a fonte, os patches, os avisos e os scripts do
componente Nintendo 3DS distribuído pelo EmuOrbit Advance. A tag
`gpl-source-2026-09-26` é a fonte atual do componente: não é um protótipo nem
uma integração experimental.

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
SHA-256 `64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c`.

## Fonte correspondente e publicação

`CORRESPONDING_SOURCE.json` identifica a composição e a tag de fonte. Antes
de distribuir um novo AAB, atualize a fonte, publique uma tag, registre a URL,
tag e hashes no repositório do app e preserve o acesso público enquanto o
binário correspondente estiver disponível. A ausência de uma ROM ou de um
binário pré-compilado neste repositório não limita os direitos concedidos pela
GPL sobre o software aqui documentado.
