package model;

public class Metrics {
    private final double precision;
    private final double recall;
    private final double auc;
    private final double kappa;
    private final double f1Score;

    public Metrics(double precision, double recall, double auc, double kappa, double f1Score) {
        this.precision = precision;
        this.recall = recall;
        this.auc = auc;
        this.kappa = kappa;
        this.f1Score = f1Score;
    }

    public double getPrecision() {
        return precision;
    }

    public double getRecall() {
        return recall;
    }

    public double getAuc() {
        return auc;
    }

    public double getKappa() {
        return kappa;
    }

    public double getF1Score() {
        return f1Score;
    }
}
