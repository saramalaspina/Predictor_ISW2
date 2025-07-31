package model;

import java.util.Locale;

public class EvaluationResult {
    private final String project;
    private final int iteration;
    private final String classifierName;
    private final String featureSelection;
    private final String sampling;
    private final String costSensitive;
    private final double precision;
    private final double recall;
    private final double auc;
    private final double kappa;
    private final double f1Score;

    public EvaluationResult(String project, int iteration, String classifierName, String featureSelection, String sampling, String costSensitive, double precision, double recall, double auc, double kappa, double f1Score) {
        this.project = project;
        this.iteration = iteration;
        this.classifierName = classifierName;
        this.featureSelection = featureSelection;
        this.sampling = sampling;
        this.costSensitive = costSensitive;
        this.precision = precision;
        this.recall = recall;
        this.auc = auc;
        this.kappa = kappa;
        this.f1Score = f1Score;
    }


    public static String getCsvHeader() {
        return "Project,Iteration,Classifier,FeatureSelection,Sampling,CostSensitive,Precision,Recall,AUC,Kappa,F1-Score";
    }

    public String toCsvString() {
        return String.format(Locale.US, "%s,%d,%s,%s,%s,%s,%.3f,%.3f,%.3f,%.3f,%.3f",
                project,
                iteration,
                classifierName,
                featureSelection,
                sampling,
                costSensitive,
                precision,
                recall,
                auc,
                kappa,
                f1Score);
    }
}
