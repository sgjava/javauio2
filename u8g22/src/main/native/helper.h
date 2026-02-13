
#ifndef U8G2_HELPER_H
#define U8G2_HELPER_H

#include "u8g2.h"
#include "u8g2port.h"

/* Lifecycle and Setup */
u8g2_t *init_u8g2(void);
void done(u8g2_t *u8g2);
void init_sdl(u8g2_t *u8g2);

/* Resolution specific setups for Jextract lookup */
void u8g2_SetupBuffer_SDL_128x64(u8g2_t *u8g2, const u8g2_cb_t *u8g2_cb);
void u8g2_SetupBuffer_SDL_256x128(u8g2_t *u8g2, const u8g2_cb_t *u8g2_cb);

/* Standard Wrappers for Java FFM */
void u8g2_InitDisplay_Java(u8g2_t *u8g2);
void u8g2_SetPowerSave_Java(u8g2_t *u8g2, uint8_t is_enable);
void u8g2_SetI2CAddress_Java(u8g2_t *u8g2, uint8_t address);
uint8_t u8g2_GetI2CAddress_Java(u8g2_t *u8g2);
uint16_t u8g2_GetDisplayWidth_Java(u8g2_t *u8g2);
uint16_t u8g2_GetDisplayHeight_Java(u8g2_t *u8g2);
int8_t u8g2_GetMaxCharHeight_Java(u8g2_t *u8g2);
int8_t u8g2_GetMaxCharWidth_Java(u8g2_t *u8g2);
uint8_t u8g2_GetDrawColor_Java(u8g2_t *u8g2);
void u8g2_SetAutoPageClear_Java(u8g2_t *u8g2, uint8_t mode);
void u8g2_SetFlipMode_Java(u8g2_t *u8g2, uint8_t mode);
void u8g2_SetContrast_Java(u8g2_t *u8g2, uint8_t value);
void u8g2_SetUserPtr_Java(u8g2_t *u8g2, void *p);
void* u8g2_GetUserPtr_Java(u8g2_t *u8g2);
uint8_t* u8g2_GetBufferPtr_Java(u8g2_t *u8g2);
size_t u8g2_GetBufferSize_Java(u8g2_t *u8g2);

#endif /* U8G2_HELPER_H */