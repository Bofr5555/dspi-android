/* Wire-compat fixture generator, V32 side.
 * Compiled against the CURRENT firmware bulk_params.h (DSPi tag v1.1.6, V32).
 * Exposes the V32 section table and the named input-config fields so that
 * hist_side.c (compiled against a historical bulk_params.h) can copy each
 * section by NAME, letting the C compiler supply both layouts. */
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include "bulk_params.h"
#include "gen.h"

#if WIRE_FORMAT_VERSION != 32
#error "v32_side.c must be compiled against the V32 header"
#endif

#define SEC(n) { #n, offsetof(WireBulkParams, n), sizeof(((WireBulkParams *)0)->n) }
const struct sec v32_secs[] = {
    SEC(header), SEC(global), SEC(crossfeed), SEC(legacy), SEC(delays),
    SEC(crosspoints), SEC(outputs), SEC(pins), SEC(eq), SEC(channel_names),
    SEC(i2s_config), SEC(leveller), SEC(preamp), SEC(master_volume),
    SEC(input_config), SEC(lg_sound_sync), SEC(user_volume), SEC(dac_hw_mute),
    SEC(crossovers), SEC(adat_config), SEC(psybass), SEC(upmix), SEC(subharm),
    SEC(tube), SEC(limiter), { 0, 0, 0 }
};

size_t v32_size(void) { return sizeof(WireBulkParams); }

void v32_input_config(const uint8_t *img, struct v32_ic *o) {
    const WireBulkParams *p = (const WireBulkParams *)img;
    memcpy(o->head8, &p->input_config, 8); /* source..i2s_rx_pin_ext[3]: same since V12 */
    memcpy(o->spdif_rx_pin_ext, p->input_config.spdif_rx_pin_ext, 3);
    o->spdif_rx_enabled_ext_p1 = p->input_config.spdif_rx_enabled_ext_p1;
    o->i2s_clock_mode = p->input_config.i2s_clock_mode;
    o->adat_input_pin = p->input_config.adat_input_pin;
    o->adat_input_enabled_p1 = p->input_config.adat_input_enabled_p1;
    o->adat_clock_mode_p1 = p->input_config.adat_clock_mode_p1;
}

/* A V32 image whose every byte is distinctive (so a misplaced byte shows in a
 * byte-level comparison), with a sane header. */
void v32_pattern(uint8_t *img) {
    size_t n = sizeof(WireBulkParams);
    for (size_t i = 0; i < n; i++) img[i] = (uint8_t)((i * 7u + 3u) ^ (i >> 8));
    WireBulkParams *p = (WireBulkParams *)img;
    memset(&p->header, 0, sizeof p->header);
    p->header.format_version = 32;
    p->header.platform_id = 1;
    p->header.num_channels = 17;
    p->header.num_output_channels = 9;
    p->header.num_input_channels = 8;
    p->header.max_bands = 12;
    p->header.payload_length = (uint16_t)n;
    p->header.fw_version_major = 1;
    p->header.fw_version_minor = 1;
}
