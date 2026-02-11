
#include "u8g2.h"
#include <stdlib.h>
#include <stdio.h>

u8g2_t *init_u8g2(void) { return (u8g2_t *) malloc(sizeof(u8g2_t)); }
void done(u8g2_t *u8g2) { free(u8g2); }

/* Standard Wrappers */
void u8g2_InitDisplay_Java(u8g2_t *u8g2) { u8g2_InitDisplay(u8g2); }
void u8g2_SetPowerSave_Java(u8g2_t *u8g2, uint8_t is_enable) { u8g2_SetPowerSave(u8g2, is_enable); }
void u8g2_SetI2CAddress_Java(u8g2_t *u8g2, uint8_t address) { u8g2_SetI2CAddress(u8g2, address); }
uint8_t u8g2_GetI2CAddress_Java(u8g2_t *u8g2) { return u8g2_GetI2CAddress(u8g2); }
uint16_t u8g2_GetDisplayWidth_Java(u8g2_t *u8g2) { return u8g2_GetDisplayWidth(u8g2); }
uint16_t u8g2_GetDisplayHeight_Java(u8g2_t *u8g2) { return u8g2_GetDisplayHeight(u8g2); }
int8_t u8g2_GetMaxCharHeight_Java(u8g2_t *u8g2) { return u8g2_GetMaxCharHeight(u8g2); }
int8_t u8g2_GetMaxCharWidth_Java(u8g2_t *u8g2) { return u8g2_GetMaxCharWidth(u8g2); }
uint8_t u8g2_GetDrawColor_Java(u8g2_t *u8g2) { return u8g2_GetDrawColor(u8g2); }
void u8g2_SetAutoPageClear_Java(u8g2_t *u8g2, uint8_t mode) { u8g2_SetAutoPageClear(u8g2, mode); }
void u8g2_SetFlipMode_Java(u8g2_t *u8g2, uint8_t mode) { u8g2_SetFlipMode(u8g2, mode); }
void u8g2_SetContrast_Java(u8g2_t *u8g2, uint8_t value) { u8g2_SetContrast(u8g2, value); }
void u8g2_SetUserPtr_Java(u8g2_t *u8g2, void *p) { u8g2_SetUserPtr(u8g2, p); }
void* u8g2_GetUserPtr_Java(u8g2_t *u8g2) { return u8g2_GetUserPtr(u8g2); }

#ifdef USE_SDL
#include <SDL2/SDL.h>
/* Dummy callback for SDL key handling */
uint8_t u8x8_sdl_key_callback(u8x8_t *u8x8, uint8_t msg, uint8_t arg_int, void *arg_ptr) { return 0; }

/* init_sdl now just handles the generic display logic if needed, 
   the setup call should happen in Java via invokeSetup */
void init_sdl(u8g2_t *u8g2) { 
    /* We leave this empty or minimal since you handle display init in Java */
}
#else
void init_sdl(u8g2_t *u8g2) { }
#endif
