#ifndef WIRECOMPAT_GEN_H
#define WIRECOMPAT_GEN_H
#include <stddef.h>
#include <stdint.h>
struct sec { const char *name; size_t off, size; };
struct v32_ic {
    uint8_t head8[8];
    uint8_t spdif_rx_pin_ext[3];
    uint8_t spdif_rx_enabled_ext_p1, i2s_clock_mode;
    uint8_t adat_input_pin, adat_input_enabled_p1, adat_clock_mode_p1;
};
extern const struct sec v32_secs[];
size_t v32_size(void);
void v32_input_config(const uint8_t *img, struct v32_ic *o);
void v32_pattern(uint8_t *img);
#endif
