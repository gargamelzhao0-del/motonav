// ESP32-S3 PIE: RGB565_SWAPPED bytes -> RGB332, in place or non-overlapping.
// Private kernel: both pointers are 16-byte aligned; a4 is a count of 16 pixels.
// All 32 input bytes are loaded before the 16 output bytes are stored.
// Instruction semantics: ESP32-S3 TRM, PIE VUNZIP.8 / ANDQ / VSL.32 / VSR.32.
    .section .iram1,"ax"
    .align 4
    .global motonav_rgb332_s3
    .type motonav_rgb332_s3,@function
motonav_rgb332_s3:
    entry a1, 32
    ee.vld.128.ip q4, a5, 16
    ee.vld.128.ip q5, a5, 16
    ee.vld.128.ip q6, a5, 16
    movi a6, 2
    movi a7, 3
    loopnez a4, .Lrgb332_end
        ee.vld.128.ip q0, a3, 16
        ee.vld.128.ip q1, a3, 16
        ee.vunzip.8 q0, q1
        ee.andq q2, q0, q5
        ee.andq q0, q0, q4
        ee.andq q1, q1, q6
        wsr.sar a6
        ee.vsl.32 q2, q2
        wsr.sar a7
        ee.vsr.32 q1, q1
        ee.orq q0, q0, q2
        ee.orq q0, q0, q1
        ee.vst.128.ip q0, a2, 16
.Lrgb332_end:
    retw.n
    .size motonav_rgb332_s3, .-motonav_rgb332_s3
