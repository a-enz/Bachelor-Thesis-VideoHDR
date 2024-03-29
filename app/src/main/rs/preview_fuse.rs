#pragma version(1)
#pragma rs java_package_name(videohdr.renderscript)
#pragma rs_fp_relaxed //floating point precision relaxed

rs_allocation gCurrentFrame;
rs_allocation gPrevFrame;
int32_t *weights;
int gFrameCounter = 0;

uchar4 __attribute__((kernel)) fuseFrames(uchar4 prevPixel, uint32_t x, uint32_t y) {

    // Read in pixel values from latest frame - YUV color space

    uchar4 curPixel;
    curPixel.r = rsGetElementAtYuv_uchar_Y(gCurrentFrame, x, y);
    curPixel.g = rsGetElementAtYuv_uchar_U(gCurrentFrame, x, y);
    curPixel.b = rsGetElementAtYuv_uchar_V(gCurrentFrame, x, y);
    curPixel.a = 255;

    int32_t curPixWeight = weights[curPixel.r];
    int32_t prevPixWeight = weights[prevPixel.r];
    int32_t sumWeights = curPixWeight + prevPixWeight;


    //curPixel.r contains the Y component, which represents luminance
    //compute the weight for this pixel, use weight from previous pixel. combine accordingly
    //use some weighting function similar to a gaussion, where middle parts have hight weight
    //and outer parts (over/underexposed have low weights)

    int4 temp;
    // simple mean value merge of the frames. would not require frame swap above

    temp.r = prevPixWeight * prevPixel.r / sumWeights + curPixWeight * curPixel.r / sumWeights;
    temp.g = prevPixWeight * prevPixel.g / sumWeights + curPixWeight * curPixel.g / sumWeights;
    temp.b = prevPixWeight * prevPixel.b / sumWeights + curPixWeight * curPixel.b / sumWeights;
    uchar4 mergedPixel = convert_uchar4(clamp(temp, 0, 255));

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
