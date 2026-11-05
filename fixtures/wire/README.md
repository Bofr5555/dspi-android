# Wire-format compatibility fixtures (core patch 0003)

Bulk parameter images (`REQ_GET_ALL_PARAMS` replies) in the wire formats of
every firmware release shipped since the V16 compatibility break, plus one
mock of a future format. Used by `rust/dspi-ffi/tests/wire_compat.rs`.
Design: `../../../docs/WIRE-FORMAT-COMPAT.md` (dspi-mobile).

## How they were made

`tools/wirecompat/gen_fixtures.sh [path to a WeebLabs/DSPi clone]`:

1. On the host, `git show <tag>:firmware/DSPi/bulk_params.h` for each tag
   below into `build/wirecompat/` (headers are not checked in).
2. In the build container, `tools/wirecompat/v32_side.c` is compiled against
   the v1.1.6 header and `tools/wirecompat/hist_side.c` against each
   historical header. `hist_side` copies every section the historical
   `WireBulkParams` has, BY NAME, from a V32 source image (so the C compiler
   supplies both layouts; nothing is hand-offset), truncating to the
   historical section size; leaves the fields that version did not have at
   zero (what that firmware's `bulk_params_collect` sends for reserved bytes);
   and writes that version's `format_version` and `payload_length`.
3. `layouts.txt` is the section table each historical header compiled to
   (offset and size of every section, and the tag's commit).

| File | Source image | Firmware tag (commit in `layouts.txt`) | Wire | Size |
|---|---|---|---|---|
| `bulk_v17.bin` / `pattern_v17.bin` | capture / pattern | v1.1.5-beta3 | 17 | 5872 |
| `bulk_v21.bin` / `pattern_v21.bin` | capture / pattern | v1.1.5-beta4 | 21 | 5876 |
| `bulk_v24.bin` / `pattern_v24.bin` | capture / pattern | v1.1.5-beta5 | 24 | 5900 |
| `bulk_v26.bin` / `pattern_v26.bin` | capture / pattern | v1.1.5-beta6 | 26 | 5944 |
| `bulk_v28.bin` / `pattern_v28.bin` | capture / pattern | v1.1.5 | 28 | 5944 |
| `bulk_v30.bin` / `pattern_v30.bin` | capture / pattern | v1.1.6-beta3 | 30 | 5980 |
| `pattern_v32.bin` | pattern | v1.1.6 | 32 | 6136 |
| `bulk_v33_mock.bin` | capture | (none: synthetic) | 33 | 6176 |

- **capture** = `../bulk_v32.bin`, the real V32 device capture (see
  `../README.md`). The `bulk_vNN.bin` files are therefore "the same settings
  as that unit, as firmware NN would report them". They are DERIVED, not
  captured from a device running that firmware; a real capture from old
  firmware would be a stronger check (no hardware was used for this work).
- **pattern** = a V32 image whose every byte is distinctive
  (`(i*7+3) ^ (i>>8)`, valid header), so a field placed one byte off cannot
  go unnoticed. `pattern_v32.bin` is the same pattern after the V32 pass
  (crossfeed `reserved2` zeroed, as every version does), the reference the
  byte-level test compares against.
- **bulk_v33_mock.bin** = the V32 capture with `format_version` 33, a
  40-byte section of `0xA5` appended and `payload_length` 6176: how a
  future append-only firmware would look.
- `bulk_v32.bin` is not repeated here (the V32 pass is the identity; the
  script checks that).

The S/PDIF 2-4 enable mask byte (`spdif_rx_enabled_ext_p1`) is copied
verbatim into the V21..V26 images (those firmwares only had S/PDIF 2-3; the
capture's value happens to be valid for them).

## SHA-256

```
0d84db55e14d98f9f9f98f32106a656037eb10bda01a2e168bc3d35c5eb4ef5b  bulk_v17.bin
33db9079f0483ce691c03c70025bc3fd9077cc50183efcd833ff9e21eece1f4f  bulk_v21.bin
62340cc4f8eb163d5f3f7a40abae659fffad860f7db5221a7475f9f882a1cd56  bulk_v24.bin
9261d390065ce776bcfebaa8b20cb8635dec3263fb3f7f1269c3e8dedb536659  bulk_v26.bin
426f7e07c8f51031046d9fa82b65fc54f92d2adf05f032298ee4dac9261f69a9  bulk_v28.bin
ec3a9ab825c457a77914ab0c36bd2c2a8f158a06a3cb6dceaf2e47aaaae4750c  bulk_v30.bin
466fcd64e248eba0185ff4621e4faf8da9865c089e4b80cdb0af605a39bb84e4  bulk_v33_mock.bin
2722cf8c8e657250769b2ce15d781dc856921f2755b4f08e94af72799c4df12a  pattern_v17.bin
02f1af1005833769f9ef8a6dfcf39d2e9eefd3ae6344c55ac55b736955e58281  pattern_v21.bin
efd3edbe8ef656770116e1009c6827800f0d7a3f9bc759f8d0a08f24f400bfec  pattern_v24.bin
b633fd3f5197e97be9b0707a2b02e2c854861d69c8eefff1123d53d1456d131c  pattern_v26.bin
bac2fff6cc2bb38d0914deb1eefd49e39d604a649832a19abf1c05081a2f5f97  pattern_v28.bin
6a31bddfc859bd319de5197beb9e7b6cda6955cf563e0f5f851dea631a86c320  pattern_v30.bin
e5c1ddce3b6a7da6b20ad1f344e212811ca6966ccebc4d58190c6e77aade0eb7  pattern_v32.bin
```
