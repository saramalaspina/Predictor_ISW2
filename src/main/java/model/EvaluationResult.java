package model;

import java.util.Locale;

public class EvaluationResult {

    public static final String CSV_HEADER = "Project,Iteration,Classifier,FeatureSelection,Sampling,CostSensitive,Precision,Recall,AUC,Kappa,F1-Score";


    private final String project;
    private final int iteration;
    private final String classifierName;
    private final String featureSelection;
    private final String sampling;
    private final String costSensitive;
    private final Metrics metrics;

    public EvaluationResult(String project, int iteration, WekaClassifier classifier, Metrics metrics) {
        this.project = project;
        this.iteration = iteration;
        this.classifierName = classifier.getName();
        this.featureSelection = classifier.getFeatureSelection();
        this.sampling = classifier.getSampling();
        this.costSensitive = classifier.getCostSensitive();
        this.metrics = metrics;

    }

    
    public String toCsvString() {
        return String.format(Locale.US, "%s,%d,%s,%s,%s,%s,%.3f,%.3f,%.3f,%.3f,%.3f",
                project,
                iteration,
                classifierName,
                featureSelection,
                sampling,
                costSensitive,
                metrics.getPrecision(),
                metrics.getRecall(),
                metrics.getAuc(),
                metrics.getKappa(),
                metrics.getF1Score());
    }
}
