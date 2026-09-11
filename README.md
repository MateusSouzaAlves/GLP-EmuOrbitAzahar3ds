# Código-fonte correspondente do componente Nintendo 3DS

Este repositório local separado contém somente o código-fonte correspondente
ao componente Nintendo 3DS preparado para o EmuOrbit Advance. Ele segue a
mesma separação usada pelo pacote público do Nintendo DS e **não contém o
aplicativo hospedeiro**, ROMs, BIOS, firmware, chaves, saves, telemetria,
monetização, credenciais ou binários pré-compilados.

O repositório ainda não possui remoto público. Enquanto o endereço público e a
revisão exata não forem registrados junto ao binário distribuído, a publicação
do recurso Nintendo 3DS permanece bloqueada.

## Conteúdo mínimo correspondente

- `third_party/azahar`: upstream Azahar 2126.0 no commit
  `fbd3fb02f71e5f9ed5134037fd59bad96c7d2b8a`, com seus submódulos recursivos;
- `nintendo3dscore/patches`: as quatro modificações aplicadas ao upstream;
- `nintendo3dscore/src/main`: frontend Android/JNI/Vulkan e scripts CMake do
  componente distribuído;
- `scripts`: aquisição, build reproduzível e auditorias de licença/binário;
- `config/nintendo3ds-source-scope.json`: versões, dependências, licenças,
  opções de build e hashes que identificam o componente;
- `nintendo3dscore/compliance`: índice de avisos e SBOM da versão.

O componente combinado é oferecido sob **GNU GPL versão 3 ou posterior**. O
Azahar upstream declara GPL-2.0-or-later; a combinação auditada usa GPL-3.0-or-
later por causa das dependências compatíveis registradas no manifesto.

## Obter os upstreams

Após clonar este repositório, inicialize tudo recursivamente:

```bash
git submodule update --init --recursive
```

Ou adquira uma cópia limpa pelo script fixado:

```powershell
.\scripts\acquire-nintendo3ds-source.ps1 -Destination .\third_party\azahar
```

## Reproduzir o core endurecido

Requisitos fixados: Android NDK `27.3.13750724`, CMake `3.30.3`, Ninja
`1.10.2`, Python 3 e Git. No Windows:

```powershell
.\scripts\build-nintendo3ds-reference-core.ps1 `
  -AzaharSource .\third_party\azahar `
  -NdkRoot C:\Android\Sdk\ndk\27.3.13750724 `
  -CMakeRoot C:\Android\Sdk\cmake\3.30.3 `
  -Ninja C:\Android\Sdk\cmake\3.30.3\bin\ninja.exe `
  -OutputDirectory .\out
```

A saída esperada é `out/azahar_libretro.so`, com 23.157.736 bytes e SHA-256
`64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c`.

## Compilar o frontend Android isolado

Use JDK 17, Android SDK 37, NDK `29.0.14206865` e CMake `3.22.1`. Aponte
`sdk.dir` em um `local.properties` não versionado e execute:

```powershell
.\gradlew.bat :nintendo3dscore:assembleRelease `
  -PEMUORBIT_N3DS_CORE_FILE=.\out\azahar_libretro.so
```

O AAR é apenas a reconstrução isolada do componente GPL. A integração e o
aplicativo EmuOrbit não fazem parte deste pacote de fonte.

## Correspondência com o binário

Antes de distribuir uma versão do EmuOrbit que contenha este componente:

1. sincronize aqui exatamente a fonte usada no build;
2. faça commit e publique este repositório sem exigir login ou pagamento;
3. registre no app e na página de distribuição a URL e o commit correspondentes;
4. mantenha essa fonte disponível enquanto o binário correspondente estiver
   sendo distribuído.

O arquivo `CORRESPONDING_SOURCE.json` permanece com estado `PRE_PUBLICATION`
até esse vínculo ser fechado. Não inclua neste repositório nenhum artefato
privado, ROM ou dado de usuário.
