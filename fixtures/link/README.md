# DSPi Link fixtures

Copied unchanged from **WeebLabs/DSPi-Link** at commit
`86f10e38dd760d01d4f589e4b606a5250b2d523d` (2026-11-02, "Analyser sharing,
output latency and lock flows: port from the Console"), fetched 2026-11-06.
MIT licence (`LICENSE.DSPi-Link`, Copyright (c) 2026 Weeb Labs).

| Directory | Upstream path | Used by |
|---|---|---|
| `frames/` | `fixtures/frames/` (`frames.json` + `*.bin`) | `rust/dspi-link/tests/fixtures.rs` (decode + byte-exact re-encode) |
| `messages/` | `fixtures/messages/*.json` | `rust/dspi-link/tests/fixtures.rs` (parse, request shapes) |
| `device/` | `fixtures/device/` (`bulk_v32.bin`, `platform.bin`, `status_wvalue9.bin`, `capture.json`) | the fake hub's simulated device (`rust/dspi-link/src/fakehub.rs`), capture checks |
| `policy/commands.json` | `policy/commands.json` | the fake hub's role classes (viewer/control/admin) |

`device/bulk_v32.bin` and `device/platform.bin` are byte-identical to
`../bulk_v32.bin` / `../platform.bin` (DSPi-Link `05949b8`, the core FFI
fixtures); the device capture did not change between the two commits.

SHA-256 (`sha256sum`):

    23bea903d678108f6c29febab040bc5ebc84ac91e5801a4c73d83dec998d79d2  ./device/bulk_v32.bin
    af307f2be49a5c0a906a463182683fcd9015824d53831dc41b912e30215dab01  ./device/capture.json
    64090dda861b20722d2ecb5deccc7e37ff76b57174a7d19da72ea70c5fac7c36  ./device/platform.bin
    57210d489b4904b9572ee6f79fbf0794557881af66a9f18bbd2cf444773c1c65  ./device/status_wvalue9.bin
    9c66b1d7ea032ca52896b4f743583742c77e80281979d509406f378af47b2f5a  ./frames/cmd_request_get_platform.bin
    85897166103a3d8c36db17da35b8cc1a0637daf9d27269f01efb7a1d8a8a66a8  ./frames/cmd_request_set_master_volume.bin
    baa5d60aa2cfbeffd441f0e00748e95035bff47a04d0934a75783c28fd9def0c  ./frames/cmd_response_denied.bin
    0e9928cc74ac6b2746020ddbc659bb8a662513daaeff6284bbd4f1d10bb6ad95  ./frames/cmd_response_platform.bin
    33025359cd74906918e30a277865e67956e21219ec8df3eb2277a109ca93a2dc  ./frames/frames.json
    ecde304783713a21fee3203a9a29b37eda49196700ec6fb60d8f91d99654ceab  ./frames/fwdata_ack.bin
    7ff138e65d6b2b6eff66f9978be9269745c7678f2bd65920a0c32279aa5197fc  ./frames/fwdata_chunk.bin
    4d889440e468fc63748dc17426736aa4719ae21e8551e174e28d3b23b8616c60  ./frames/notify_param_changed.bin
    6faffcd9d050ddb85fedefcb827bdf885db158775a86d00281a6f248373935f3  ./frames/poll_status.bin
    4ac9d120d0a568e100c1480b2301fa38203f6261ad6a9cc6f8f256c65b312365  ./frames/resync_reattached.bin
    d3d8951f2393213f59fa4033f6637bf0b9cd20845f10e4bbcf3923b60a937e51  ./LICENSE.DSPi-Link
    c392a2fff2752a10c08df3a5425d10542edc7f9be32473cc5c4fb28bce7d5bdc  ./messages/auth_list.json
    750db38bdb7fb10d8a75c6f55ea45fb0c0343b34e83bfc0de533a32a32d4cf77  ./messages/auth_list_ok.json
    e5a8b270d9eab1965b563780f46a1b3b433dd29acbb43a31f4a36eb2802c531c  ./messages/auth_ok.json
    b922919a3885d4be48264493ca7b87e1c3bb952fc518b24080684605d110993d  ./messages/auth_pair.json
    4e7039d791e1ad481175dddf683da7b5e237e4dbe8ae6635cefde75b91b35e85  ./messages/auth_revoke.json
    baef1d02e38ff07a7386b526cab1985210839dfeb6d557774dc5e1a4f481543e  ./messages/auth_token.json
    250beb0af4a52cb156527b559d130446b1d032f8cbd980111e1be0c59792af70  ./messages/device_added.json
    30c083c0ef09b252d8c79d879d51e61d043297f1c7a13c93e4da5d144ffc6e54  ./messages/device_list.json
    6ea05a9a6d70f0f90a101e96fc19a7748c364d8ca01dc7ac04d1993a23d691e8  ./messages/device_list_ok.json
    2d9b7bb285af7007bbd6399f1cc019c510a48dddaa7830bc38b2a607b0c44a76  ./messages/device_removed.json
    fe857146e4f336e4ff01cc087b159862ce5cc60840664323d3d0300b7c76007a  ./messages/device_snapshot.json
    7adbf1858b45443a513cc785556012b0eed51d34e454424960268a32dd97254f  ./messages/err_bad_field.json
    0c084b5a88b952eecd90f16115c005e28597feed3380696d86f8d9dbb84108c8  ./messages/err_denied.json
    3e7e6acbd4b1f4e48d40d3d62541ec939840fe5b8ac32b22cd283eeeb8f292b2  ./messages/err_unauthenticated.json
    0a2b6589f65c8b8d778f31b183bb714682cdcb7b694b61fb13a033539f7d4130  ./messages/hello_client.json
    adb98b2ff65abbf21b9675d18da60137daa10640543e01f9ddc12c6b26154a11  ./messages/hello_hub_bridge.json
    50dfcf9af9d8aa810f835721371964dc52258b8b5a183c7d02162181ed17d0c0  ./messages/hello_hub.json
    9c3942f355d13611a046d4142d6180cf670f726eb06cdaf6d97c2c81adc54e83  ./messages/hub_auth_set_none.json
    595cc5a2ef44bbf8a3b51f4c17e09c67446b24a5856fba4513b292566281152c  ./messages/hub_auth_set_ok.json
    4d30e0ae0e740f8a9430e4a8b7f2ca942aecf08b3e12a1af7e9ca285412fddd6  ./messages/hub_auth_set_pin.json
    e1108fabf2257a5f6396d0eb67853599bf29353ee20301dcaa068ee154f07f4d  ./messages/hub_config_changed.json
    d2d3521a514b8f3de2a2a617f5f87f4bebe3b81e1ac6fadad9efb72745cff9a9  ./messages/hub_config_get.json
    27a70ebbe4fcec6c4dff2332d62d1aa39af1caafe23025a2989e3f8847212784  ./messages/hub_config_get_ok.json
    8ee34b90e70ce45339086b7022be8bdeb34689e05a1e11ba143d45447b06a34b  ./messages/hub_config_set.json
    f66525964198bf1319f7340efa4060e8244d5e0d7ed490d4f3038da47b16280f  ./messages/hub_reset.json
    af69881877113bf575a0b4b723cb033684957a816864295bb921d5b9db6dc42a  ./messages/hub_restart.json
    a40db70d66c59d80db30a23dfeb850f689b60ba6f651f5bd4b9b0ff75e44e1fb  ./messages/hub_update_done.json
    905a19ec591cf91aeace6ad82f07bbb7a8498146143964cfe64bb8b9de16bc45  ./messages/hub_update.json
    1ce3b226ae6b2c89238b5ca555a75ca916159f0cf13c5c15a96bc9674393fe5f  ./messages/hub_wifi_join.json
    fdf6a002dccbf7e5edc2e547c1ccb8fbb3c3ccf204e8544ffdb8196cdbbd57ce  ./messages/hub_wifi_scan.json
    d28fb3a1977100092c140c4cf28e6069737516330bf2675cb55d628a23947dc9  ./messages/hub_wifi_scan_ok.json
    18d9139e1b99d25003c4bfc8eddcdaac75f15178cac89ab07e223646e795d7ea  ./messages/lock_acquire.json
    f4270d524366afba40c244801c9a28cab9e15571bb9a279b2b935a65298ce39f  ./messages/lock_release.json
    d4b239663d2547e446c71addb8fc845afa9e346ab6527236d55b161c7f1646e1  ./messages/poll_subscribe.json
    39d09aa20f3d0a72cccefc91e1c7594a43ed7ca95fb937e0391fe25f6a767c23  ./messages/poll_subscribe_ok.json
    437e6910ca4a72b93010d34ce8e3d243c1b5012ac80e2d78ef4d8c457841de50  ./messages/poll_unsubscribe.json
    d0fe096fbe50b866564f26053e4b7e0b61d60f69dd0be428a5d07c8c98388170  ./policy/commands.json
