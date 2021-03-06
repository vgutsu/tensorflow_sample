package com.raywenderlich.android.tflclassifier.classification.tflite;

import android.app.Activity;

import java.io.IOException;

/**
 * This TensorFlowLite classifier works with the float MobileNet model.
 */
public class ClassifierFloatMobileNet extends Classifier {

    /**
     * An array to hold inference results, to be feed into Tensorflow Lite as outputs. This isn't part
     * of the super class, because we need a primitive array here.
     */
    private float[][] labelProbArray = null;

    /**
     * Initializes a {@code ClassifierFloatMobileNet}.
     *
     * @param activity
     */
    public ClassifierFloatMobileNet(Activity activity, Device device, int numThreads)
            throws IOException {
        super(activity, device, numThreads);
        labelProbArray = new float[1][getNumLabels()];
    }

    @Override
    public int getImageSizeX() {
        return 299;
    }

    @Override
    public int getImageSizeY() {
        return 299;
    }

    @Override
    protected String getModelPath() {
        // you can download this file from
        // see build.gradle for where to obtain this file. It should be auto
        // downloaded into assets.
        return MODEL_PATH;
    }

    @Override
    protected String getLabelPath() {
        return LABEL_PATH;
    }

    @Override
    protected int getNumBytesPerChannel() {
        return 4; // Float.SIZE / Byte.SIZE;
    }

    @Override
    protected void addPixelValue(int pixelValue) {
        imgData.putFloat(((pixelValue >> 16) & 0xFF) / 255.f);
        imgData.putFloat(((pixelValue >> 8) & 0xFF) / 255.f);
        imgData.putFloat((pixelValue & 0xFF) / 255.f);
    }

    @Override
    protected float getProbability(int labelIndex) {
        return labelProbArray[0][labelIndex];
    }

    @Override
    protected void setProbability(int labelIndex, Number value) {
        labelProbArray[0][labelIndex] = value.floatValue();
    }

    @Override
    protected float getNormalizedProbability(int labelIndex) {
        return labelProbArray[0][labelIndex];
    }

    @Override
    protected void runInference() {
        tflite.run(imgData, labelProbArray);
    }
}