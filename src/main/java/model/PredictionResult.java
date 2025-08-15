package model;

public class PredictionResult {
    public final double probability;
    public final int loc;
    public final boolean isBuggy;

    public PredictionResult(double probability, int loc, boolean isBuggy) {
        this.probability = probability;
        this.loc = loc;
        this.isBuggy = isBuggy;
    }
}
