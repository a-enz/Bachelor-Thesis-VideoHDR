#pragma version(1)
#pragma rs java_package_name(aenz.renderscript)
#pragma rs_fp_relaxed //floating point precision relaxed

rs_allocation gCurrentFrame;
rs_allocation gPrevFrame;

int gFrameCounter = 0;

uchar4 __attribute__((kernel)) fuseFrames(uchar4 prevPixel, uint32_t x, uint32_t y) {

    // Read in pixel values from latest frame - YUV color space

    uchar4 curPixel;
    curPixel.r = rsGetElementAtYuv_uchar_Y(gCurrentFrame, x, y);
    curPixel.g = rsGetElementAtYuv_uchar_U(gCurrentFrame, x, y);
    curPixel.b = rsGetElementAtYuv_uchar_V(gCurrentFrame, x, y);
    curPixel.a = 255;

    uchar4 mergedPixel;
    uchar4 evenFramePixel;
    uchar4 oddFramePixel;



    /*This first IF is in preparation for more complex merging techniques where it is
    necessairy to switch around the input (dark/bright) frames because the operator is not symetric
    */
    if(gFrameCounter & 0x1) { //true if gFrameCounter is an uneven number
    /* uneven framecounter means by convention that:
        gPrevFrame: data of shorter exposure (darker picture)
        gCurrentFrame: data of longer exposure (brighter picture)
    */
        evenFramePixel = prevPixel; //darker frame
        oddFramePixel = curPixel; //brighter frame

    } else { //...gFrameCounter is an even number

    /* even framecounter means that:
        gPrevFrame: data of longer exposure (brighter picture)
        gCurrentFrame: data of shorter exposure (darker picture)
    */

        evenFramePixel = curPixel; //darker frame
        oddFramePixel = prevPixel; //brighter frame

    }

    // simple mean value merge of the frames. would not require frame swap above

    mergedPixel = evenFramePixel / 2 + oddFramePixel / 2;

    // Convert YUV to RGB, JFIF transform with fixed-point math
    // R = Y + 1.402 * (V - 128)
    // G = Y - 0.34414 * (U - 128) - 0.71414 * (V - 128)
    // B = Y + 1.772 * (U - 128)

    int4 rgb;
    rgb.r = mergedPixel.r +
            mergedPixel.b * 1436 / 1024 - 179;
    rgb.g = mergedPixel.r -
            mergedPixel.g * 46549 / 131072 + 44 -
            mergedPixel.b * 93604 / 131072 + 91;
    rgb.b = mergedPixel.r +
            mergedPixel.g * 1814 / 1024 - 227;
    rgb.a = 255;

    // Store current pixel for next frame
    rsSetElementAt_uchar4(gPrevFrame, curPixel, x, y);

    // Write out merged HDR result
    uchar4 out = convert_uchar4(clamp(rgb, 0, 255));

    return out;
}