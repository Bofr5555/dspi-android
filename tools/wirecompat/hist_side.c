/* Wire-compat fixture generator, historical side.
 * Compiled against a HISTORICAL firmware bulk_params.h (the shipped tag is
 * passed as -DTAG). Converts a V32 image into what that firmware's
 * bulk_params_collect() would emit for the same settings: every section the
 * historical struct has is copied by name from the V32 image (truncated to the
 * historical size), fields that did not exist yet are left zero (the firmware
 * zeroes reserved bytes on collect), and the header carries the historical
 * version and sizeof.
 *
 *   hist <in_v32.bin|--pattern> <out.bin> [--layout]
 *   hist --newer <in_v32.bin> <out.bin>   (only with the V32 header: V33 mock)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "bulk_params.h"
#include "gen.h"

#ifndef TAG
#define TAG "?"
#endif

static uint8_t src[8192];

static const struct sec *v32(const char *name) {
    for (const struct sec *s = v32_secs; s->name; s++)
        if (!strcmp(s->name, name)) return s;
    fprintf(stderr, "no V32 section %s\n", name);
    exit(2);
}

static void copy_sec(uint8_t *dst, size_t off, size_t size, const char *name) {
    const struct sec *s = v32(name);
    memcpy(dst + off, src + s->off, size < s->size ? size : s->size);
}
#define COPY(n) copy_sec((uint8_t *)&out, offsetof(WireBulkParams, n), sizeof out.n, #n)
#define LAYOUT(n) printf("  %-14s off %5zu size %5zu\n", #n, offsetof(WireBulkParams, n), sizeof out.n)

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage\n"); return 2; }
    size_t n32 = v32_size();
    if (!strcmp(argv[1], "--pattern")) {
        v32_pattern(src);
    } else {
        FILE *f = fopen(argv[1], "rb");
        if (!f || fread(src, 1, n32, f) != n32) { fprintf(stderr, "read %s\n", argv[1]); return 1; }
        fclose(f);
    }
    if (src[0] != 32) { fprintf(stderr, "input is not V32\n"); return 1; }

    WireBulkParams out;
    memset(&out, 0, sizeof out);
    COPY(header); COPY(global); COPY(crossfeed); COPY(legacy); COPY(delays);
    COPY(crosspoints); COPY(outputs); COPY(pins); COPY(eq); COPY(channel_names);
    COPY(i2s_config); COPY(leveller); COPY(preamp); COPY(master_volume);
    COPY(lg_sound_sync); COPY(user_volume); COPY(dac_hw_mute); COPY(crossovers);
#if WIRE_FORMAT_VERSION >= 17
    COPY(adat_config);
#endif
#if WIRE_FORMAT_VERSION >= 23
    COPY(psybass);
#endif
#if WIRE_FORMAT_VERSION >= 25
    COPY(upmix);
#endif
#if WIRE_FORMAT_VERSION >= 29
    COPY(subharm); /* 16 bytes at V29 (prefix), 36 at V30+ */
#endif
#if WIRE_FORMAT_VERSION >= 31
    COPY(tube);
#endif
#if WIRE_FORMAT_VERSION >= 32
    COPY(limiter);
#endif

    /* Header: this firmware's version and size. */
    out.header.format_version = WIRE_FORMAT_VERSION;
    out.header.payload_length = (uint16_t)sizeof(WireBulkParams);

    /* Input config, field by field (V28 shifted the tail by one byte). */
    struct v32_ic ic;
    v32_input_config(src, &ic);
    memset(&out.input_config, 0, sizeof out.input_config);
    memcpy(&out.input_config, ic.head8, 8);
#if WIRE_FORMAT_VERSION >= 21
    for (unsigned k = 0; k < sizeof out.input_config.spdif_rx_pin_ext; k++)
        out.input_config.spdif_rx_pin_ext[k] = ic.spdif_rx_pin_ext[k];
    out.input_config.spdif_rx_enabled_ext_p1 = ic.spdif_rx_enabled_ext_p1;
    out.input_config.i2s_clock_mode = ic.i2s_clock_mode;
#endif
#if WIRE_FORMAT_VERSION >= 24
    out.input_config.adat_input_pin = ic.adat_input_pin;
    out.input_config.adat_input_enabled_p1 = ic.adat_input_enabled_p1;
    out.input_config.adat_clock_mode_p1 = ic.adat_clock_mode_p1;
#endif

    /* Fields that were still reserved in this version: the firmware zeroes them. */
#if WIRE_FORMAT_VERSION < 19
    memset(out.global.reserved, 0, sizeof out.global.reserved);           /* loudness_output_mask */
#endif
#if WIRE_FORMAT_VERSION < 20
    out.crossfeed.reserved = 0;                                           /* output_pair_mask */
#endif
#if WIRE_FORMAT_VERSION < 21
    memset(out.i2s_config.reserved, 0, sizeof out.i2s_config.reserved);   /* clock_pin_mode_p1, bck_pin_slave */
#endif
    out.crossfeed.reserved2 = 0;                                          /* reserved at every version */
#if WIRE_FORMAT_VERSION < 22
    for (int c = 0; c < WIRE_MAX_CHANNELS; c++) {                         /* Linkwitz Transform Q */
        for (int b = 0; b < WIRE_MAX_BANDS; b++) memset(out.eq[c][b].reserved, 0, 2);
        for (int b = 0; b < WIRE_MAX_XOVER_BANDS; b++) memset(out.crossovers.bands[c][b].reserved, 0, 2);
    }
#endif
#if WIRE_FORMAT_VERSION >= 25 && WIRE_FORMAT_VERSION < 26
    out.upmix.reserved = 0;                                               /* presence_q1 */
#endif

    int newer = argc > 3 && !strcmp(argv[3], "--newer");
    FILE *g = fopen(argv[2], "wb");
    if (!g) return 1;
    fwrite(&out, 1, sizeof out, g);
    if (newer) {
        /* V33 mock: a future firmware that appended a 40-byte section. */
        uint8_t tail[40];
        memset(tail, 0xA5, sizeof tail);
        fwrite(tail, 1, sizeof tail, g);
    }
    fclose(g);
    if (newer) {
        FILE *h = fopen(argv[2], "r+b");
        uint8_t hdr[8] = {0};
        fread(hdr, 1, 8, h);
        hdr[0] = 33;
        uint16_t len = (uint16_t)(sizeof out + 40);
        hdr[6] = (uint8_t)len; hdr[7] = (uint8_t)(len >> 8);
        fseek(h, 0, SEEK_SET);
        fwrite(hdr, 1, 8, h);
        fclose(h);
    }

    if (argc > 3 && !strcmp(argv[3], "--layout")) {
        printf("%s: V%d sizeof(WireBulkParams) = %zu\n", TAG, WIRE_FORMAT_VERSION, sizeof out);
        LAYOUT(header); LAYOUT(global); LAYOUT(crossfeed); LAYOUT(legacy); LAYOUT(delays);
        LAYOUT(crosspoints); LAYOUT(outputs); LAYOUT(pins); LAYOUT(eq); LAYOUT(channel_names);
        LAYOUT(i2s_config); LAYOUT(leveller); LAYOUT(preamp); LAYOUT(master_volume);
        LAYOUT(input_config); LAYOUT(lg_sound_sync); LAYOUT(user_volume); LAYOUT(dac_hw_mute);
        LAYOUT(crossovers);
#if WIRE_FORMAT_VERSION >= 17
        LAYOUT(adat_config);
#endif
#if WIRE_FORMAT_VERSION >= 23
        LAYOUT(psybass);
#endif
#if WIRE_FORMAT_VERSION >= 25
        LAYOUT(upmix);
#endif
#if WIRE_FORMAT_VERSION >= 29
        LAYOUT(subharm);
#endif
#if WIRE_FORMAT_VERSION >= 31
        LAYOUT(tube);
#endif
#if WIRE_FORMAT_VERSION >= 32
        LAYOUT(limiter);
#endif
    }
    return 0;
}
